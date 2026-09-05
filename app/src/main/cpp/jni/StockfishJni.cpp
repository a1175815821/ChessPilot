// In-process Stockfish JNI wrapper (alternative to the subprocess build).
//
// Targets Stockfish 16.1 (tag sf_16.1). Its main() is:
//     Stockfish::init();
//     Stockfish::UCI::loop(argc, argv);
// We call those directly on a dedicated thread and redirect std::cout into a JNI
// callback so the Kotlin side gets "info ..."/"bestmove ..." lines as they stream.
//
// Build with -DENABLE_JNI_ENGINE=ON. For most users the subprocess build (default)
// is preferable: engine crashes never take Duolingo down, and UCI runs over pipes
// without touching std::cout.
#include <jni.h>
#include <string>
#include <sstream>
#include <thread>
#include <mutex>
#include <android/log.h>

#include "bitboard.h"
#include "position.h"
#include "thread.h"
#include "uci.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SF-JNI", __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_callback = nullptr;
static std::mutex g_out_mutex;
static std::ostringstream g_cout_redirect;
static std::streambuf* g_old_buf = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static void jni_emit(const std::string& line) {
    if (!g_vm || !g_callback) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID mid = env->GetMethodID(cls, "onUciLine", "(Ljava/lang/String;)V");
    if (mid) {
        jstring jline = env->NewStringUTF(line.c_str());
        env->CallVoidMethod(g_callback, mid, jline);
        env->DeleteLocalRef(jline);
    }
    env->DeleteLocalRef(cls);
    if (attached) g_vm->DetachCurrentThread();
}

// Pump whatever UCI printed into JNI (called from the UCI thread when idle is impractical —
// instead we install a streambuf that forwards on flush).
class JniOutBuf : public std::streambuf {
protected:
    int_type overflow(int_type ch) override {
        if (ch == traits_type::eof()) return traits_type::eof();
        buffer_.push_back(traits_type::to_char_type(ch));
        if (ch == '\n') { flush_line(); }
        return traits_type::not_eof(ch);
    }
    void flush_line() {
        if (buffer_.empty()) return;
        jni_emit(std::string(buffer_.begin(), buffer_.end() - 1));
        buffer_.clear();
    }
    std::string buffer_;
} g_out_buf;

extern "C"
JNIEXPORT void JNICALL
Java_com_chessassist_engine_StockfishJni_start(JNIEnv* env, jobject, jobject callback) {
    if (g_callback) { env->DeleteGlobalRef(g_callback); }
    g_callback = env->NewGlobalRef(callback);

    g_old_buf = std::cout.rdbuf(&g_out_buf);

    std::thread([] {
        char argv0[] = "stockfish";
        char* argv[] = { argv0, nullptr };
        Stockfish::init();
        Stockfish::UCI::loop(1, argv);
        std::cout.rdbuf(g_old_buf);
    }).detach();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_chessassist_engine_StockfishJni_send(JNIEnv* env, jobject, jstring cmd) {
    // EXPERIMENTAL STUB: the in-process variant is not wired end-to-end.
    // UCI::loop reads the real stdin; pushing into std::cin via operator<< does not
    // compile (istream has no operator<<) and would not reach the engine anyway.
    // Kept compiling so -DENABLE_JNI_ENGINE=ON configures; use the default
    // subprocess engine (StockfishClient) for real analysis.
    if (env == nullptr || cmd == nullptr) return;
    const char* c = env->GetStringUTFChars(cmd, nullptr);
    LOGI("jni send (stub, dropped): %s", c ? c : "");
    if (c) env->ReleaseStringUTFChars(cmd, c);
}
