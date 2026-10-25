cmake_minimum_required(VERSION 3.22)
project(spark_bridge)

# Prebuilt Cactus engine, staged by CI for arm64-v8a only:
#   needle download android-arm64 --out app/engine/android-arm64
# which provides libneedle.a + needle.h.
if(NOT DEFINED NEEDLE_ENGINE_DIR)
  set(NEEDLE_ENGINE_DIR "${CMAKE_SOURCE_DIR}/engine")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(CACTUS_ABI "android-arm64")
else()
  # No prebuilt engine for this ABI (emulators, x86): build a stub so the
  # APK still assembles; NeedleBridge reports nativeAvailable=false.
  set(CACTUS_ABI "")
endif()

add_library(spark_bridge SHARED src/main/cpp/needle_bridge.cpp)

if(CACTUS_ABI)
  target_include_directories(spark_bridge PRIVATE ${NEEDLE_ENGINE_DIR}/${CACTUS_ABI})
  set(NEEDLE_LIB "${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}/libneedle.a")
  if(EXISTS "${NEEDLE_LIB}")
    message(STATUS "Linking prebuilt engine: ${NEEDLE_LIB}")
    target_link_libraries(spark_bridge ${NEEDLE_LIB} log)
  else()
    message(WARNING "libneedle.a not found in ${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}; run the engine download step first.")
    target_link_libraries(spark_bridge log)
  endif()
else()
  message(WARNING "No Cactus engine for ${ANDROID_ABI}; building stub.")
  target_compile_definitions(spark_bridge PRIVATE SPARK_STUB)
  target_link_libraries(spark_bridge log)
endif()
