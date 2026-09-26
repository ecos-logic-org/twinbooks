// JNI bridge for org.ecos.logic.twinbooks.translation.SlimtNative
#include <android/log.h>
#include <jni.h>

#include <exception>
#include <string>
#include <vector>

#include "slimt_engine.hh"

#define LOG_TAG "SlimtNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using twinbooks::SlimtEngine;

namespace {

// Paths only: GetStringUTFChars yields *modified* UTF-8, fine for file names.
std::string to_string(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

// Book text travels as standard UTF-8 byte arrays (modified UTF-8 would mangle
// characters outside the BMP, e.g. emoji, in both directions).
std::string to_string(JNIEnv *env, jbyteArray bytes) {
  if (bytes == nullptr) return {};
  const jsize length = env->GetArrayLength(bytes);
  std::string out(static_cast<size_t>(length), '\0');
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
  return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &value) {
  const auto length = static_cast<jsize>(value.size());
  jbyteArray out = env->NewByteArray(length);
  env->SetByteArrayRegion(out, 0, length, reinterpret_cast<const jbyte *>(value.data()));
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_ecos_logic_twinbooks_translation_SlimtNative_nativeCreate(
    JNIEnv *env, jobject /* this */, jstring model, jstring vocabulary,
    jstring shortlist) {
  try {
    auto *engine = new SlimtEngine(to_string(env, model),
                                   to_string(env, vocabulary),
                                   to_string(env, shortlist));
    return reinterpret_cast<jlong>(engine);
  } catch (const std::exception &e) {
    LOGE("nativeCreate: %s", e.what());
  } catch (...) {
    LOGE("nativeCreate: unknown C++ exception");
  }
  return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_org_ecos_logic_twinbooks_translation_SlimtNative_nativeTranslate(
    JNIEnv *env, jobject /* this */, jlong handle, jobjectArray texts) {
  auto *engine = reinterpret_cast<SlimtEngine *>(handle);
  if (engine == nullptr) return nullptr;

  const jsize count = env->GetArrayLength(texts);
  std::vector<std::string> sources;
  sources.reserve(count);
  for (jsize i = 0; i < count; ++i) {
    auto text = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
    sources.push_back(to_string(env, text));
    env->DeleteLocalRef(text);
  }

  std::vector<std::string> targets;
  try {
    targets = engine->translate(std::move(sources));
  } catch (const std::exception &e) {
    LOGE("nativeTranslate: %s", e.what());
    return nullptr;
  } catch (...) {
    LOGE("nativeTranslate: unknown C++ exception");
    return nullptr;
  }

  jclass byte_array_class = env->FindClass("[B");
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(targets.size()),
                                         byte_array_class, nullptr);
  for (size_t i = 0; i < targets.size(); ++i) {
    jbyteArray value = to_bytes(env, targets[i]);
    env->SetObjectArrayElement(out, static_cast<jsize>(i), value);
    env->DeleteLocalRef(value);
  }
  return out;
}

JNIEXPORT void JNICALL
Java_org_ecos_logic_twinbooks_translation_SlimtNative_nativeDestroy(
    JNIEnv * /* env */, jobject /* this */, jlong handle) {
  delete reinterpret_cast<SlimtEngine *>(handle);
}

}  // extern "C"
