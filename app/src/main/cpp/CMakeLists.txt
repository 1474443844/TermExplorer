cmake_minimum_required(VERSION 3.10.2)
project("terminal-pty")

add_library(terminal-pty SHARED terminal-pty.cpp)

find_library(log-lib log)
target_link_libraries(terminal-pty ${log-lib})
