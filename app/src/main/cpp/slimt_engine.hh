// Thin C++ facade over slimt used by the JNI layer (and by host-side tests).
#pragma once

#include <memory>
#include <string>
#include <vector>

namespace twinbooks {

class SlimtEngine {
 public:
  /// Loads a Bergamot/Marian model package (uncompressed files).
  /// Encoder/decoder depth is read from the model; throws on failure.
  SlimtEngine(const std::string &model_path, const std::string &vocabulary_path,
              const std::string &shortlist_path);
  ~SlimtEngine();

  SlimtEngine(const SlimtEngine &) = delete;
  SlimtEngine &operator=(const SlimtEngine &) = delete;

  /// Translates each text independently; output has the same size and order.
  std::vector<std::string> translate(std::vector<std::string> texts);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace twinbooks
