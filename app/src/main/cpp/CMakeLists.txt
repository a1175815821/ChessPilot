cmake_minimum_required(VERSION 3.22.1)
project(chessassist)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Stockfish: clone official sources into cpp/stockfish/src via tools/fetch_stockfish.py
# Built as an EXECUTABLE named libstockfish.so so the Android package manager extracts
# it into nativeLibraryDir, where exec() is permitted (targetSdk >= 29 W^X policy).
# This is the same proven approach DroidFish uses.
# ---------------------------------------------------------------------------
set(SF_SRC ${CMAKE_CURRENT_SOURCE_DIR}/stockfish/src)

file(GLOB_RECURSE SF_SOURCES ${SF_SRC}/*.cpp)
# SF 19 added src/universal/ (multi-arch runtime dispatch: per-arch entry
# points + macOS slice embedding). Our plain arm64 build needs none of it —
# entry_riscv64/entry_x86 don't even compile for aarch64 — so drop the dir.
list(FILTER SF_SOURCES EXCLUDE REGEX ".*/universal/.*")

add_executable(stockfish-bin ${SF_SOURCES})

target_include_directories(stockfish-bin PRIVATE ${SF_SRC} ${SF_SRC}/nnue)

target_compile_definitions(stockfish-bin PRIVATE
    # Net (nn-1a298aa575a0.nnue, SFNNv16, sitting next to evaluate.cpp) is
    # embedded via INCBIN (see SF's nnue/network.cpp) so the engine never needs
    # external net files (no filesDir juggling, no EvalFile setoptions to get
    # wrong). SF 19 retired the secondary small net: only the single default
    # net (EvalFileDefaultName in evaluate.h) is needed.
    USE_PTHREADS
    NO_PREFETCH
    USE_NEON            # arm64: NEON for NNUE (fast path)
)

target_compile_options(stockfish-bin PRIVATE -O3 -fexceptions -frtti -march=armv8-a+simd)

set_target_properties(stockfish-bin PROPERTIES
    OUTPUT_NAME "libstockfish"
    PREFIX ""
    SUFFIX ".so"
)

# Place the executable where the package installer extracts native libs.
set_target_properties(stockfish-bin PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)

# NOTE: an in-process JNI variant used to live here (StockfishJni.kt/.cpp,
# -DENABLE_JNI_ENGINE). Deleted: it was an unwired logging stub, and the
# subprocess engine is the crash-isolated production path. See git history
# if the experiment is ever revived.
