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

add_executable(stockfish-bin ${SF_SOURCES})

target_include_directories(stockfish-bin PRIVATE ${SF_SRC} ${SF_SRC}/nnue)

target_compile_definitions(stockfish-bin PRIVATE
    # Nets (nn-b1a57edbea57.nnue + nn-baff1ede1f90.nnue, sitting next to
    # evaluate.cpp) are embedded via INCBIN so the engine never needs external
    # net files (no filesDir juggling, no EvalFile setoptions to get wrong).
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

# ---------------------------------------------------------------------------
# Optional in-process JNI variant (alternative to subprocess; see StockfishJni.kt).
# Build with -DENABLE_JNI_ENGINE=ON if you prefer linking Stockfish into the app.
# ---------------------------------------------------------------------------
option(ENABLE_JNI_ENGINE "Build in-process JNI engine" OFF)
if (ENABLE_JNI_ENGINE)
    add_library(stockfish_jni SHARED jni/StockfishJni.cpp ${SF_SOURCES})
    target_include_directories(stockfish_jni PRIVATE ${SF_SRC} ${SF_SRC}/nnue)
    target_compile_definitions(stockfish_jni PRIVATE
        USE_PTHREADS NO_PREFETCH USE_NEON)
    target_compile_options(stockfish_jni PRIVATE -O3 -fexceptions -frtti -march=armv8-a+simd)
    target_link_libraries(stockfish_jni PRIVATE log)
endif()
