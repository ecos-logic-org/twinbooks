#include "slimt_engine.hh"

#include <cstddef>
#include <cstring>
#include <mutex>
#include <utility>

#include "slimt/Frontend.hh"
#include "slimt/Io.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"

namespace twinbooks {

namespace {

// slimt doesn't read the architecture from the model file. Marian names its
// parameters "encoder_l<N>_..." / "decoder_l<N>_..." (1-based), so the highest N
// is the layer count. Approach taken from slimt-sys (MIT, David Ventura).
void detect_layer_counts(const std::string &model_path, size_t &encoder_layers,
                         size_t &decoder_layers) {
  encoder_layers = 0;
  decoder_layers = 0;
  slimt::io::MmapFile mmap(model_path);
  auto items = slimt::io::load_items(mmap.data());
  for (const auto &item : items) {
    const std::string &name = item.name;
    auto check_prefix = [&](const char *prefix, size_t &out) {
      const size_t plen = std::strlen(prefix);
      if (name.compare(0, plen, prefix) != 0) return;
      size_t idx = 0;
      size_t pos = plen;
      while (pos < name.size() && name[pos] >= '0' && name[pos] <= '9') {
        idx = idx * 10 + static_cast<size_t>(name[pos] - '0');
        ++pos;
      }
      if (pos != plen && idx > out) out = idx;
    };
    check_prefix("encoder_l", encoder_layers);
    check_prefix("decoder_l", decoder_layers);
  }
}

}  // namespace

struct SlimtEngine::Impl {
  std::shared_ptr<slimt::Model> model;
  slimt::Blocking service{slimt::Config{}};
  std::mutex mutex;  // Blocking keeps a request counter: one caller at a time
};

SlimtEngine::SlimtEngine(const std::string &model_path,
                         const std::string &vocabulary_path,
                         const std::string &shortlist_path)
    : impl_(std::make_unique<Impl>()) {
  slimt::Package<std::string> package;
  package.model = model_path;
  package.vocabulary = vocabulary_path;
  package.shortlist = shortlist_path;

  size_t encoder_layers = 0;
  size_t decoder_layers = 0;
  detect_layer_counts(model_path, encoder_layers, decoder_layers);

  slimt::Model::Config config = slimt::preset::tiny();
  if (encoder_layers > 0) config.encoder_layers = encoder_layers;
  if (decoder_layers > 0) config.decoder_layers = decoder_layers;

  impl_->model = std::make_shared<slimt::Model>(config, package);
}

SlimtEngine::~SlimtEngine() = default;

std::vector<std::string> SlimtEngine::translate(std::vector<std::string> texts) {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  slimt::Options options;
  std::vector<slimt::Response> responses =
      impl_->service.translate(impl_->model, std::move(texts), options);

  std::vector<std::string> out;
  out.reserve(responses.size());
  for (auto &response : responses) {
    out.push_back(std::move(response.target.text));
  }
  return out;
}

}  // namespace twinbooks
