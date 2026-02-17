cmake_minimum_required(VERSION 3.22.1)
project("omt_vmx_jni")

add_library(omt_vmx_jni SHARED vmx_jni.cpp)
target_link_libraries(omt_vmx_jni android log)
