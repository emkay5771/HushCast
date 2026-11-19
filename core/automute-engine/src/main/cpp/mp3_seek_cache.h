// Seek tables for dr_mp3, built once per file and shared by every decoder of that file.
//
// Without a seek table, drmp3_seek_to_pcm_frame decodes from the start of the stream, so each DAI
// clip of a long episode costs a decode of everything before it.  A table holds a byte offset
// about every 2 s; a seek jumps to the nearest point before the target and decodes 16 MP3 frames
// of lead-in (DRMP3_SEEK_LEADING_MP3_FRAMES) so the decoder state matches a linear read.
//
// Include after dr_mp3.h.  Tables are keyed by path + size + mtime and kept in a small LRU.
#pragma once

#include <sys/stat.h>

#include <cstdint>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace hushcast {

using Mp3SeekTable = std::vector<drmp3_seek_point>;

struct Mp3FileKey {
    std::string path;
    int64_t size = -1;
    int64_t mtimeNs = 0;

    bool operator==(const Mp3FileKey & o) const {
        return size == o.size && mtimeNs == o.mtimeNs && path == o.path;
    }
};

inline bool mp3FileKey(const std::string & path, Mp3FileKey & out) {
    struct stat st {};
    if (path.empty() || stat(path.c_str(), &st) != 0) return false;
    out.path = path;
    out.size = (int64_t) st.st_size;
#if defined(__APPLE__)
    out.mtimeNs = (int64_t) st.st_mtimespec.tv_sec * 1000000000LL + (int64_t) st.st_mtimespec.tv_nsec;
#else
    out.mtimeNs = (int64_t) st.st_mtim.tv_sec * 1000000000LL + (int64_t) st.st_mtim.tv_nsec;
#endif
    return true;
}

class Mp3SeekCache {
public:
    explicit Mp3SeekCache(size_t capacity) : capacity_(capacity) {}

    std::shared_ptr<const Mp3SeekTable> get(const Mp3FileKey & key) {
        std::lock_guard<std::mutex> lock(mu_);
        for (auto it = entries_.begin(); it != entries_.end(); ++it) {
            if (it->first == key) {
                entries_.splice(entries_.begin(), entries_, it);  // most recent first
                return entries_.front().second;
            }
        }
        return nullptr;
    }

    void put(const Mp3FileKey & key, std::shared_ptr<const Mp3SeekTable> table) {
        std::lock_guard<std::mutex> lock(mu_);
        for (auto it = entries_.begin(); it != entries_.end(); ) {
            // Same path with another size/mtime is a re-downloaded file: its old table is useless.
            if (it->first.path == key.path) it = entries_.erase(it); else ++it;
        }
        entries_.emplace_front(key, std::move(table));
        while (entries_.size() > capacity_) entries_.pop_back();
    }

    size_t size() {
        std::lock_guard<std::mutex> lock(mu_);
        return entries_.size();
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mu_);
        entries_.clear();
    }

private:
    std::mutex mu_;
    size_t capacity_;
    std::list<std::pair<Mp3FileKey, std::shared_ptr<const Mp3SeekTable>>> entries_;
};

inline Mp3SeekCache & mp3SeekCache() {
    static Mp3SeekCache cache(4);
    return cache;
}

constexpr double kMp3SeekPointSpacingSec = 2.0;
constexpr drmp3_uint32 kMp3MaxSeekPoints = 16384;

// Scans the stream's frame headers (no decoding) and returns about one seek point per 2 s.
// Leaves the decoder at the start of the stream.
inline std::shared_ptr<const Mp3SeekTable> buildMp3SeekTable(drmp3 * mp3) {
    const drmp3_uint64 pcmFrames = drmp3_get_pcm_frame_count(mp3);
    const drmp3_uint64 rate = mp3->sampleRate ? mp3->sampleRate : 44100;
    drmp3_uint64 want = pcmFrames / (drmp3_uint64) (rate * kMp3SeekPointSpacingSec);
    if (want < 1) want = 1;
    if (want > kMp3MaxSeekPoints) want = kMp3MaxSeekPoints;
    drmp3_uint32 count = (drmp3_uint32) want;
    auto table = std::make_shared<Mp3SeekTable>(count);
    if (!drmp3_calculate_seek_points(mp3, &count, table->data()) || count == 0) return nullptr;
    table->resize(count);
    return table;
}

// Binds the file's seek table to [mp3], building and caching it on a miss.  The caller keeps the
// returned pointer for as long as the table stays bound (dr_mp3 does not copy it).
inline std::shared_ptr<const Mp3SeekTable> bindCachedMp3SeekTable(drmp3 * mp3, const std::string & path) {
    Mp3FileKey key;
    if (!mp3FileKey(path, key)) return nullptr;
    auto table = mp3SeekCache().get(key);
    if (!table) {
        table = buildMp3SeekTable(mp3);
        if (!table) return nullptr;
        mp3SeekCache().put(key, table);
    }
    // dr_mp3 only reads the points; the const_cast is for its C signature.
    drmp3_bind_seek_table(mp3, (drmp3_uint32) table->size(), const_cast<drmp3_seek_point *>(table->data()));
    return table;
}

}  // namespace hushcast
