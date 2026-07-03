cmake_minimum_required(VERSION 3.20)

foreach(required_env IN ITEMS INSTANT_CLIENT_HOME POSTGRESQL_HOME CLANG_HOME)
  if(NOT DEFINED ENV{${required_env}} OR "$ENV{${required_env}}" STREQUAL "")
    message(FATAL_ERROR "Environment variable ${required_env} must be set.")
  endif()
endforeach()

if(DEFINED ENV{CLANG_HOME} AND NOT CMAKE_C_COMPILER)
  file(TO_CMAKE_PATH "$ENV{CLANG_HOME}" CLANG_HOME)
  if(CMAKE_HOST_WIN32 AND CMAKE_GENERATOR MATCHES "Visual Studio" AND EXISTS "${CLANG_HOME}/bin/clang-cl.exe")
    set(CMAKE_C_COMPILER "${CLANG_HOME}/bin/clang-cl.exe" CACHE FILEPATH "C compiler" FORCE)
  elseif(EXISTS "${CLANG_HOME}/bin/clang")
    set(CMAKE_C_COMPILER "${CLANG_HOME}/bin/clang" CACHE FILEPATH "C compiler" FORCE)
  elseif(EXISTS "${CLANG_HOME}/bin/clang.exe")
    set(CMAKE_C_COMPILER "${CLANG_HOME}/bin/clang.exe" CACHE FILEPATH "C compiler" FORCE)
  elseif(CMAKE_HOST_WIN32 AND EXISTS "${CLANG_HOME}/bin/clang-cl.exe")
    set(CMAKE_C_COMPILER "${CLANG_HOME}/bin/clang-cl.exe" CACHE FILEPATH "C compiler" FORCE)
  else()
    message(FATAL_ERROR "CLANG_HOME is set to '${CLANG_HOME}', but no clang compiler was found under '${CLANG_HOME}/bin'.")
  endif()
endif()

project(oracle_fdw C)

option(ORACLE_FDW_VERBOSE_BUILD "Prefer verbose build output where the selected CMake generator supports it" ON)
if(ORACLE_FDW_VERBOSE_BUILD)
  set(CMAKE_VERBOSE_MAKEFILE ON CACHE BOOL "Show full build command lines for Makefile generators" FORCE)
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED OFF)
set(CMAKE_C_EXTENSIONS ON)

option(ORACLE_FDW_BUILD_JNI_FDW "Build oracle_jni_fdw PostgreSQL extension module" ON)
option(ORACLE_FDW_BUILD_UTILS_DEMO "Build oracle_utils behavior comparison demo executables" ON)

if(ORACLE_FDW_VERBOSE_BUILD AND CMAKE_GENERATOR MATCHES "Ninja")
  message(STATUS "Ninja ignores CMAKE_VERBOSE_MAKEFILE; use 'cmake --build <build-dir> --verbose' or 'ninja -v' to show full commands.")
endif()

file(TO_CMAKE_PATH "$ENV{INSTANT_CLIENT_HOME}" INSTANT_CLIENT_HOME)
file(TO_CMAKE_PATH "$ENV{POSTGRESQL_HOME}" POSTGRESQL_HOME)
file(TO_CMAKE_PATH "$ENV{CLANG_HOME}" CLANG_HOME)

set(ORACLE_FDW_SQL_FILES
  oracle_fdw--1.2.sql
  oracle_fdw--1.0--1.1.sql
  oracle_fdw--1.1--1.2.sql
)

set(ORACLE_JNI_FDW_SQL_FILES
  oracle_jni_fdw--1.0.sql
)

set(ORACLE_FDW_SOURCES
  oracle_fdw.c
  oracle_utils.c
  oracle_gis.c
)

if(MSVC)
  list(APPEND ORACLE_FDW_SOURCES msvc/oracle_msvc.c)
endif()

find_program(PG_CONFIG_EXECUTABLE
  NAMES pg_config pg_config.exe
  PATHS "${POSTGRESQL_HOME}/bin"
  NO_DEFAULT_PATH
)

function(pg_config_value option output_variable)
  if(PG_CONFIG_EXECUTABLE)
    execute_process(
      COMMAND "${PG_CONFIG_EXECUTABLE}" "${option}"
      OUTPUT_VARIABLE value
      OUTPUT_STRIP_TRAILING_WHITESPACE
      ERROR_QUIET
    )
    if(value)
      file(TO_CMAKE_PATH "${value}" value)
      set(${output_variable} "${value}" PARENT_SCOPE)
    endif()
  endif()
endfunction()

pg_config_value("--includedir-server" PG_INCLUDEDIR_SERVER)
pg_config_value("--includedir" PG_INCLUDEDIR)
pg_config_value("--pkglibdir" PG_PKGLIBDIR)
pg_config_value("--sharedir" PG_SHAREDIR)
pg_config_value("--cppflags" PG_CPPFLAGS)
pg_config_value("--cflags" PG_CFLAGS)
pg_config_value("--ldflags" PG_LDFLAGS)

function(split_flags flags output_variable)
  if(flags)
    separate_arguments(flag_list NATIVE_COMMAND "${flags}")
    set(${output_variable} "${flag_list}" PARENT_SCOPE)
  endif()
endfunction()

function(filter_pg_compile_flags input_list_variable output_variable)
  set(filtered_flags)
  set(skip_next FALSE)
  foreach(flag IN LISTS ${input_list_variable})
    if(skip_next)
      set(skip_next FALSE)
    elseif(flag STREQUAL "-I" OR flag STREQUAL "-isystem")
      set(skip_next TRUE)
    elseif(flag MATCHES "^-I.+" OR flag MATCHES "^-isystem.+" OR flag MATCHES "^--sysroot=.*" OR flag MATCHES "^--target=.*")
      continue()
    else()
      list(APPEND filtered_flags "${flag}")
    endif()
  endforeach()
  set(${output_variable} "${filtered_flags}" PARENT_SCOPE)
endfunction()

function(filter_pg_link_flags input_list_variable output_variable)
  set(filtered_flags)
  set(skip_next FALSE)
  foreach(flag IN LISTS ${input_list_variable})
    if(skip_next)
      set(skip_next FALSE)
    elseif(flag STREQUAL "-L")
      set(skip_next TRUE)
    elseif(flag MATCHES "^-L.+" OR flag MATCHES "^--sysroot=.*" OR flag MATCHES "^--target=.*" OR flag MATCHES "^-Wl,-rpath-link,")
      continue()
    else()
      list(APPEND filtered_flags "${flag}")
    endif()
  endforeach()
  set(${output_variable} "${filtered_flags}" PARENT_SCOPE)
endfunction()

split_flags("${PG_CPPFLAGS}" PG_CPPFLAGS_LIST)
split_flags("${PG_CFLAGS}" PG_CFLAGS_LIST)
split_flags("${PG_LDFLAGS}" PG_LDFLAGS_LIST)
set(PG_COMPILE_FLAGS_LIST ${PG_CPPFLAGS_LIST} ${PG_CFLAGS_LIST})
filter_pg_compile_flags(PG_COMPILE_FLAGS_LIST PG_FILTERED_COMPILE_FLAGS_LIST)
filter_pg_link_flags(PG_LDFLAGS_LIST PG_FILTERED_LDFLAGS_LIST)

set(POSTGRESQL_INCLUDE_DIRS
  "${POSTGRESQL_HOME}/include/server/port"
  "${POSTGRESQL_HOME}/include/server"
  "${POSTGRESQL_HOME}/include/internal"
  "${POSTGRESQL_HOME}/include"
)

if(WIN32)
  list(PREPEND POSTGRESQL_INCLUDE_DIRS
    "${POSTGRESQL_HOME}/include/postgresql/server/port/win32_msvc"
    "${POSTGRESQL_HOME}/include/postgresql/server/port/win32"
    "${POSTGRESQL_HOME}/include/postgresql/server"
    "${POSTGRESQL_HOME}/include/postgresql/internal"
    "${POSTGRESQL_HOME}/include/server/port/win32_msvc"
    "${POSTGRESQL_HOME}/include/server/port/win32"
  )
endif()

if(PG_INCLUDEDIR_SERVER)
  list(PREPEND POSTGRESQL_INCLUDE_DIRS "${PG_INCLUDEDIR_SERVER}")
endif()
if(PG_INCLUDEDIR)
  list(PREPEND POSTGRESQL_INCLUDE_DIRS "${PG_INCLUDEDIR}")
endif()

set(ORACLE_INCLUDE_DIRS
  "${INSTANT_CLIENT_HOME}/sdk/include"
  "${INSTANT_CLIENT_HOME}/oci/include"
  "${INSTANT_CLIENT_HOME}/rdbms/public"
  "${INSTANT_CLIENT_HOME}"
)

set(ORACLE_LIBRARY_DIRS
  "${INSTANT_CLIENT_HOME}/sdk/lib/msvc"
  "${INSTANT_CLIENT_HOME}"
  "${INSTANT_CLIENT_HOME}/bin"
  "${INSTANT_CLIENT_HOME}/lib"
  "${INSTANT_CLIENT_HOME}/lib/amd64"
)

foreach(path_list_name IN ITEMS POSTGRESQL_INCLUDE_DIRS ORACLE_INCLUDE_DIRS ORACLE_LIBRARY_DIRS)
  set(existing_paths)
  foreach(path IN LISTS ${path_list_name})
    if(EXISTS "${path}")
      list(APPEND existing_paths "${path}")
    endif()
  endforeach()
  list(REMOVE_DUPLICATES existing_paths)
  set(${path_list_name} "${existing_paths}")
endforeach()

if(NOT POSTGRESQL_INCLUDE_DIRS)
  message(FATAL_ERROR "No PostgreSQL include directory was found below POSTGRESQL_HOME='${POSTGRESQL_HOME}'.")
endif()

if(NOT ORACLE_INCLUDE_DIRS)
  message(FATAL_ERROR "No Oracle include directory was found below INSTANT_CLIENT_HOME='${INSTANT_CLIENT_HOME}'.")
endif()

find_library(POSTGRESQL_LIBRARY
  NAMES postgres
  PATHS "${POSTGRESQL_HOME}/lib"
  NO_DEFAULT_PATH
)

if(WIN32 OR CYGWIN)
  set(ORACLE_LIBRARY_NAME oci)
else()
  set(ORACLE_LIBRARY_NAME clntsh)
endif()

find_library(ORACLE_LIBRARY
  NAMES "${ORACLE_LIBRARY_NAME}"
  PATHS ${ORACLE_LIBRARY_DIRS}
  NO_DEFAULT_PATH
)

if(NOT ORACLE_LIBRARY)
  message(FATAL_ERROR "Oracle library '${ORACLE_LIBRARY_NAME}' was not found below INSTANT_CLIENT_HOME='${INSTANT_CLIENT_HOME}'.")
endif()

if(WIN32 AND NOT POSTGRESQL_LIBRARY)
  message(FATAL_ERROR "PostgreSQL import library 'postgres' was not found below POSTGRESQL_HOME='${POSTGRESQL_HOME}/lib'.")
endif()

add_library(oracle_fdw MODULE ${ORACLE_FDW_SOURCES})

target_compile_definitions(oracle_fdw PRIVATE
  ORACLE_FDW_HANDLER=oracle_fdw_handler_oci
  ORACLE_FDW_VALIDATOR=oracle_fdw_validator_oci
  ORACLE_FDW_CLOSE_CONNECTIONS=oracle_close_connections_oci
  ORACLE_FDW_DIAG=oracle_diag_oci
  ORACLE_FDW_EXECUTE=oracle_execute_oci
)

target_include_directories(oracle_fdw PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}"
  ${POSTGRESQL_INCLUDE_DIRS}
  ${ORACLE_INCLUDE_DIRS}
)

target_compile_options(oracle_fdw PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})

target_link_libraries(oracle_fdw PRIVATE ${ORACLE_LIBRARY})

if(POSTGRESQL_LIBRARY)
  target_link_libraries(oracle_fdw PRIVATE ${POSTGRESQL_LIBRARY})
endif()

target_link_options(oracle_fdw PRIVATE ${PG_FILTERED_LDFLAGS_LIST})

if(MSVC)
  target_compile_definitions(oracle_fdw PRIVATE _CRT_SECURE_NO_WARNINGS)
  target_compile_options(oracle_fdw PRIVATE /W4 /wd4200 /wd4244 /wd4267 /wd4389 /wd4018 /wd4996 /wd4100 /wd4127)
  target_link_libraries(oracle_fdw PRIVATE delayimp)
  target_link_options(oracle_fdw PRIVATE /DELAYLOAD:oci.dll)
endif()

if(ORACLE_FDW_BUILD_JNI_FDW OR ORACLE_FDW_BUILD_UTILS_DEMO)
  if(NOT DEFINED ENV{JDK17_HOME} OR "$ENV{JDK17_HOME}" STREQUAL "")
    message(FATAL_ERROR "Environment variable JDK17_HOME must be set when ORACLE_FDW_BUILD_JNI_FDW or ORACLE_FDW_BUILD_UTILS_DEMO is ON.")
  endif()
  file(TO_CMAKE_PATH "$ENV{JDK17_HOME}" JDK17_HOME_INPUT)
  set(JDK17_HOME_CANDIDATES
    "${JDK17_HOME_INPUT}"
    "${JDK17_HOME_INPUT}/jdk"
  )
  set(JDK17_ROOT)
  foreach(candidate IN LISTS JDK17_HOME_CANDIDATES)
    if(EXISTS "${candidate}/include/jni.h")
      set(JDK17_ROOT "${candidate}")
      break()
    endif()
  endforeach()
  if(NOT JDK17_ROOT)
    message(FATAL_ERROR "JDK17_HOME='${JDK17_HOME_INPUT}' must point to a JDK root, or a wrapper directory containing a 'jdk/' subdirectory with headers.")
  endif()
  set(JDK17_BIN_PATHS
    "${JDK17_HOME_INPUT}/bin"
    "${JDK17_ROOT}/bin"
  )
  list(REMOVE_DUPLICATES JDK17_BIN_PATHS)
  find_program(JAVA_EXECUTABLE
    NAMES java java.exe
    PATHS ${JDK17_BIN_PATHS}
    NO_DEFAULT_PATH
    REQUIRED
  )
  find_program(JAVAC_EXECUTABLE
    NAMES javac javac.exe
    PATHS ${JDK17_BIN_PATHS}
    NO_DEFAULT_PATH
    REQUIRED
  )
  find_program(JAR_EXECUTABLE
    NAMES jar jar.exe
    PATHS ${JDK17_BIN_PATHS}
    NO_DEFAULT_PATH
    REQUIRED
  )
  execute_process(
    COMMAND "${JAVAC_EXECUTABLE}" -version
    OUTPUT_VARIABLE JAVAC_VERSION_OUTPUT
    ERROR_VARIABLE JAVAC_VERSION_ERROR
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_STRIP_TRAILING_WHITESPACE
  )
  set(JAVAC_VERSION_TEXT "${JAVAC_VERSION_OUTPUT}${JAVAC_VERSION_ERROR}")
  if(NOT JAVAC_VERSION_TEXT MATCHES "javac[ ]+(1[7-9]|[2-9][0-9])\\.")
    message(FATAL_ERROR "JDK17_HOME must point to JDK 17 or newer; got '${JAVAC_VERSION_TEXT}'.")
  endif()

  set(JDK17_INCLUDE_DIRS
    "${JDK17_ROOT}/include"
  )
  if(WIN32)
    list(APPEND JDK17_INCLUDE_DIRS "${JDK17_ROOT}/include/win32")
  elseif(APPLE)
    list(APPEND JDK17_INCLUDE_DIRS "${JDK17_ROOT}/include/darwin")
  else()
    list(APPEND JDK17_INCLUDE_DIRS "${JDK17_ROOT}/include/linux")
  endif()

  find_library(JVM_LIBRARY
    NAMES jvm
    PATHS "${JDK17_ROOT}/lib" "${JDK17_ROOT}/lib/server" "${JDK17_ROOT}/jre/lib/server"
    NO_DEFAULT_PATH
    REQUIRED
  )

  set(ORACLE_FDW_DEFAULT_JDBC_JAR "${INSTANT_CLIENT_HOME}/ojdbc17.jar")
  if(NOT EXISTS "${ORACLE_FDW_DEFAULT_JDBC_JAR}")
    message(FATAL_ERROR "JDBC driver '${ORACLE_FDW_DEFAULT_JDBC_JAR}' was not found. Put ojdbc17.jar below INSTANT_CLIENT_HOME or set ORACLE_FDW_JDBC_JAR at runtime.")
  endif()

  set(ORACLE_FDW_JNI_CLASSES_DIR "${CMAKE_CURRENT_BINARY_DIR}/jni_classes")
  set(ORACLE_FDW_JNI_BRIDGE_JAR "${CMAKE_CURRENT_BINARY_DIR}/oracle_fdw_jni_backend.jar")
  add_custom_command(
    OUTPUT "${ORACLE_FDW_JNI_BRIDGE_JAR}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${ORACLE_FDW_JNI_CLASSES_DIR}"
    COMMAND "${JAVAC_EXECUTABLE}" -encoding UTF-8 --release 17
            -d "${ORACLE_FDW_JNI_CLASSES_DIR}"
            "${CMAKE_CURRENT_SOURCE_DIR}/jni_backend/java/OracleJdbcBackend.java"
    COMMAND "${JAR_EXECUTABLE}" cf "${ORACLE_FDW_JNI_BRIDGE_JAR}" -C "${ORACLE_FDW_JNI_CLASSES_DIR}" .
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/jni_backend/java/OracleJdbcBackend.java"
    COMMENT "Building Oracle JDBC JNI bridge jar"
    VERBATIM
  )
  add_custom_target(oracle_jni_java DEPENDS "${ORACLE_FDW_JNI_BRIDGE_JAR}")
endif()

if(ORACLE_FDW_BUILD_JNI_FDW)
  add_library(oracle_jni_fdw MODULE
    oracle_fdw.c
    jni_backend/oracle_utils_jni.c
    jni_backend/oracle_gis_stub.c
  )

  add_dependencies(oracle_jni_fdw oracle_jni_java)
  target_compile_definitions(oracle_jni_fdw PRIVATE
    ORACLE_FDW_MODULE_NAME=oracle_jni_fdw
    ORACLE_FDW_HANDLER=oracle_fdw_handler_jni
    ORACLE_FDW_VALIDATOR=oracle_fdw_validator_jni
    ORACLE_FDW_CLOSE_CONNECTIONS=oracle_close_connections_jni
    ORACLE_FDW_DIAG=oracle_diag_jni
    ORACLE_FDW_EXECUTE=oracle_execute_jni
    ORACLE_FDW_JNI_BRIDGE_JAR="${ORACLE_FDW_JNI_BRIDGE_JAR}"
    ORACLE_FDW_DEFAULT_JDBC_JAR="${ORACLE_FDW_DEFAULT_JDBC_JAR}"
  )
  target_include_directories(oracle_jni_fdw PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${POSTGRESQL_INCLUDE_DIRS}
    ${JDK17_INCLUDE_DIRS}
  )
  target_compile_options(oracle_jni_fdw PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})
  target_link_libraries(oracle_jni_fdw PRIVATE ${JVM_LIBRARY})
  if(POSTGRESQL_LIBRARY)
    target_link_libraries(oracle_jni_fdw PRIVATE ${POSTGRESQL_LIBRARY})
  endif()
  target_link_options(oracle_jni_fdw PRIVATE ${PG_FILTERED_LDFLAGS_LIST})

  if(MSVC)
    target_compile_definitions(oracle_jni_fdw PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_compile_options(oracle_jni_fdw PRIVATE /W4 /wd4200 /wd4244 /wd4267 /wd4389 /wd4018 /wd4996 /wd4100 /wd4127)
  endif()
endif()

if(ORACLE_FDW_BUILD_UTILS_DEMO)
  find_program(CARGO_EXECUTABLE cargo REQUIRED)

  set(ORACLE_RS_CARGO_PROFILE_DIR debug)
  set(ORACLE_RS_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rust_backend/oracle-rs")
  set(ORACLE_RS_CARGO_ARGS build --manifest-path "${ORACLE_RS_SOURCE_DIR}/Cargo.toml")
  if(CMAKE_BUILD_TYPE MATCHES "^[Rr]elease|^[Rr]elWithDebInfo|^[Mm]inSizeRel")
    list(APPEND ORACLE_RS_CARGO_ARGS --release)
    set(ORACLE_RS_CARGO_PROFILE_DIR release)
  endif()

  set(ORACLE_RS_CARGO_TARGET "" CACHE STRING "Rust target triple for oracle-rs static library")
  if(WIN32 AND NOT ORACLE_RS_CARGO_TARGET)
    if(MSVC)
      set(ORACLE_RS_CARGO_TARGET "x86_64-pc-windows-msvc" CACHE STRING "Rust target triple for oracle-rs static library" FORCE)
    else()
      set(ORACLE_RS_CARGO_TARGET "x86_64-pc-windows-gnu" CACHE STRING "Rust target triple for oracle-rs static library" FORCE)
    endif()
  endif()
  if(ORACLE_RS_CARGO_TARGET)
    list(APPEND ORACLE_RS_CARGO_ARGS --target "${ORACLE_RS_CARGO_TARGET}")
  endif()

  set(ORACLE_RS_TARGET_DIR "${CMAKE_CURRENT_BINARY_DIR}/cargo")
  if(ORACLE_RS_CARGO_TARGET)
    set(ORACLE_RS_CARGO_OUTPUT_DIR "${ORACLE_RS_TARGET_DIR}/${ORACLE_RS_CARGO_TARGET}/${ORACLE_RS_CARGO_PROFILE_DIR}")
  else()
    set(ORACLE_RS_CARGO_OUTPUT_DIR "${ORACLE_RS_TARGET_DIR}/${ORACLE_RS_CARGO_PROFILE_DIR}")
  endif()
  if(ORACLE_RS_CARGO_TARGET MATCHES "msvc$")
    set(ORACLE_RS_STATICLIB "${ORACLE_RS_CARGO_OUTPUT_DIR}/oracle_rs.lib")
  else()
    set(ORACLE_RS_STATICLIB "${ORACLE_RS_CARGO_OUTPUT_DIR}/liboracle_rs.a")
  endif()

  file(GLOB_RECURSE ORACLE_RS_SOURCES CONFIGURE_DEPENDS
    "${ORACLE_RS_SOURCE_DIR}/src/*.rs"
    "${ORACLE_RS_SOURCE_DIR}/Cargo.toml"
    "${ORACLE_RS_SOURCE_DIR}/Cargo.lock"
  )

  add_custom_command(
    OUTPUT "${ORACLE_RS_STATICLIB}"
    COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${ORACLE_RS_TARGET_DIR}" "${CARGO_EXECUTABLE}" ${ORACLE_RS_CARGO_ARGS}
    WORKING_DIRECTORY "${ORACLE_RS_SOURCE_DIR}"
    DEPENDS ${ORACLE_RS_SOURCES}
    COMMENT "Building oracle-rs static library"
    VERBATIM
  )

  add_custom_target(oracle_rs_staticlib DEPENDS "${ORACLE_RS_STATICLIB}")

  add_library(oracle_rs_backend STATIC IMPORTED GLOBAL)
  set_target_properties(oracle_rs_backend PROPERTIES IMPORTED_LOCATION "${ORACLE_RS_STATICLIB}")
  add_dependencies(oracle_rs_backend oracle_rs_staticlib)

  add_library(oracle_utils_demo_support STATIC test/oracle_utils_demo_support.c)
  target_include_directories(oracle_utils_demo_support PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${POSTGRESQL_INCLUDE_DIRS}
  )
  target_compile_options(oracle_utils_demo_support PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})

  add_executable(oracle_utils_demo_oci
    test/oracle_utils_demo.c
    oracle_utils.c
  )

  target_compile_definitions(oracle_utils_demo_oci PRIVATE USE_OCI_BACKEND)
  target_include_directories(oracle_utils_demo_oci PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${POSTGRESQL_INCLUDE_DIRS}
    ${ORACLE_INCLUDE_DIRS}
  )
  target_compile_options(oracle_utils_demo_oci PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})
  target_link_libraries(oracle_utils_demo_oci PRIVATE oracle_utils_demo_support ${ORACLE_LIBRARY})
  if(POSTGRESQL_LIBRARY)
    target_link_libraries(oracle_utils_demo_oci PRIVATE ${POSTGRESQL_LIBRARY})
  endif()
  target_link_options(oracle_utils_demo_oci PRIVATE ${PG_FILTERED_LDFLAGS_LIST})

  add_executable(oracle_utils_demo_rust
    test/oracle_utils_demo.c
    rust_backend/oracle_rust_pg_callbacks.c
  )

  target_compile_definitions(oracle_utils_demo_rust PRIVATE USE_RUST_BACKEND)
  target_compile_definitions(oracle_utils_demo_rust PRIVATE ORACLE_RUST_PG_CALLBACKS_OMIT_GEOMETRY)
  target_include_directories(oracle_utils_demo_rust PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${POSTGRESQL_INCLUDE_DIRS}
  )
  target_compile_options(oracle_utils_demo_rust PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})
  target_link_libraries(oracle_utils_demo_rust PRIVATE oracle_utils_demo_support oracle_rs_backend)
  target_link_options(oracle_utils_demo_rust PRIVATE ${PG_FILTERED_LDFLAGS_LIST})

  add_executable(oracle_utils_demo_jni
    test/oracle_utils_demo.c
    jni_backend/oracle_utils_jni.c
  )

  add_dependencies(oracle_utils_demo_jni oracle_jni_java)
  target_compile_definitions(oracle_utils_demo_jni PRIVATE
    USE_JNI_BACKEND
    ORACLE_FDW_JNI_BRIDGE_JAR="${ORACLE_FDW_JNI_BRIDGE_JAR}"
    ORACLE_FDW_DEFAULT_JDBC_JAR="${ORACLE_FDW_DEFAULT_JDBC_JAR}"
  )
  target_include_directories(oracle_utils_demo_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${POSTGRESQL_INCLUDE_DIRS}
    ${JDK17_INCLUDE_DIRS}
  )
  target_compile_options(oracle_utils_demo_jni PRIVATE ${PG_FILTERED_COMPILE_FLAGS_LIST})
  target_link_libraries(oracle_utils_demo_jni PRIVATE oracle_utils_demo_support ${JVM_LIBRARY})
  target_link_options(oracle_utils_demo_jni PRIVATE ${PG_FILTERED_LDFLAGS_LIST})

  if(WIN32)
    target_link_libraries(oracle_utils_demo_rust PRIVATE
      advapi32 bcrypt crypt32 iphlpapi ncrypt ntdll secur32 userenv ws2_32
    )
    if(NOT MSVC)
      target_link_libraries(oracle_utils_demo_rust PRIVATE winpthread)
      foreach(runtime_dll IN ITEMS libwinpthread-1.dll libunwind.dll)
        unset(ORACLE_FDW_RUNTIME_DLL CACHE)
        find_file(ORACLE_FDW_RUNTIME_DLL
          NAMES "${runtime_dll}"
          PATHS "${CLANG_HOME}/bin"
          NO_DEFAULT_PATH
        )
        if(ORACLE_FDW_RUNTIME_DLL)
          add_custom_command(TARGET oracle_utils_demo_rust POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                    "${ORACLE_FDW_RUNTIME_DLL}"
                    "$<TARGET_FILE_DIR:oracle_utils_demo_rust>"
            COMMENT "Copying ${runtime_dll} for oracle_utils_demo_rust"
            VERBATIM
          )
        endif()
      endforeach()
    endif()
  else()
    target_link_libraries(oracle_utils_demo_rust PRIVATE dl m pthread)
  endif()

  if(MSVC)
    target_compile_definitions(oracle_utils_demo_oci PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_compile_definitions(oracle_utils_demo_rust PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_compile_definitions(oracle_utils_demo_jni PRIVATE _CRT_SECURE_NO_WARNINGS)
  endif()

  add_test(NAME oracle_utils_demo_oci COMMAND oracle_utils_demo_oci)
  add_test(NAME oracle_utils_demo_rust COMMAND oracle_utils_demo_rust)
  add_test(NAME oracle_utils_demo_jni COMMAND oracle_utils_demo_jni)
  if(WIN32)
    file(TO_NATIVE_PATH "${INSTANT_CLIENT_HOME}" INSTANT_CLIENT_HOME_NATIVE)
    file(TO_NATIVE_PATH "${JDK17_HOME}/bin" JDK17_BIN_NATIVE)
    file(TO_NATIVE_PATH "${JDK17_HOME}/bin/server" JDK17_SERVER_BIN_NATIVE)
    set_tests_properties(oracle_utils_demo_oci PROPERTIES
      ENVIRONMENT "PATH=${INSTANT_CLIENT_HOME_NATIVE}\\;$ENV{PATH}"
    )
    set_tests_properties(oracle_utils_demo_jni PROPERTIES
      ENVIRONMENT "PATH=${JDK17_SERVER_BIN_NATIVE}\\;${JDK17_BIN_NATIVE}\\;$ENV{PATH}"
    )
  endif()
endif()

set_target_properties(oracle_fdw PROPERTIES
  PREFIX ""
  OUTPUT_NAME "oracle_fdw"
)

if(TARGET oracle_jni_fdw)
  set_target_properties(oracle_jni_fdw PROPERTIES
    PREFIX ""
    OUTPUT_NAME "oracle_jni_fdw"
  )
endif()

if(WIN32)
  set_target_properties(oracle_fdw PROPERTIES SUFFIX ".dll")
  if(TARGET oracle_jni_fdw)
    set_target_properties(oracle_jni_fdw PROPERTIES SUFFIX ".dll")
  endif()
endif()

set(ORACLE_FDW_PKGLIBDIR "${PG_PKGLIBDIR}" CACHE PATH "PostgreSQL pkglibdir for oracle_fdw library installation")
set(ORACLE_FDW_EXTENSIONDIR "${PG_SHAREDIR}/extension" CACHE PATH "PostgreSQL extension directory for oracle_fdw SQL/control files")
set(ORACLE_FDW_DOCDIR "${PG_SHAREDIR}/doc/extension" CACHE PATH "PostgreSQL extension documentation directory")

if(NOT ORACLE_FDW_PKGLIBDIR)
  set(ORACLE_FDW_PKGLIBDIR "${POSTGRESQL_HOME}/lib" CACHE PATH "PostgreSQL pkglibdir for oracle_fdw library installation" FORCE)
endif()
if(NOT ORACLE_FDW_EXTENSIONDIR OR ORACLE_FDW_EXTENSIONDIR STREQUAL "/extension")
  set(ORACLE_FDW_EXTENSIONDIR "${POSTGRESQL_HOME}/share/extension" CACHE PATH "PostgreSQL extension directory for oracle_fdw SQL/control files" FORCE)
endif()
if(NOT ORACLE_FDW_DOCDIR OR ORACLE_FDW_DOCDIR STREQUAL "/doc/extension")
  set(ORACLE_FDW_DOCDIR "${POSTGRESQL_HOME}/share/doc/extension" CACHE PATH "PostgreSQL extension documentation directory" FORCE)
endif()

install(TARGETS oracle_fdw
  LIBRARY DESTINATION "${ORACLE_FDW_PKGLIBDIR}"
  RUNTIME DESTINATION "${ORACLE_FDW_PKGLIBDIR}"
)

if(TARGET oracle_jni_fdw)
  install(TARGETS oracle_jni_fdw
    LIBRARY DESTINATION "${ORACLE_FDW_PKGLIBDIR}"
    RUNTIME DESTINATION "${ORACLE_FDW_PKGLIBDIR}"
  )
  install(FILES "${ORACLE_FDW_JNI_BRIDGE_JAR}"
    DESTINATION "${ORACLE_FDW_PKGLIBDIR}"
  )
endif()

install(FILES oracle_fdw.control ${ORACLE_FDW_SQL_FILES}
  DESTINATION "${ORACLE_FDW_EXTENSIONDIR}"
)

if(TARGET oracle_jni_fdw)
  install(FILES oracle_jni_fdw.control ${ORACLE_JNI_FDW_SQL_FILES}
    DESTINATION "${ORACLE_FDW_EXTENSIONDIR}"
  )
endif()

install(FILES README.oracle_fdw
  DESTINATION "${ORACLE_FDW_DOCDIR}"
)

enable_testing()

find_program(PG_REGRESS_EXECUTABLE
  NAMES pg_regress pg_regress.exe
  PATHS "${POSTGRESQL_HOME}/lib/postgresql/pgxs/src/test/regress"
        "${POSTGRESQL_HOME}/lib/pgxs/src/test/regress"
        "${POSTGRESQL_HOME}/bin"
)

if(PG_REGRESS_EXECUTABLE)
  add_test(
    NAME oracle_fdw_regress
    COMMAND "${PG_REGRESS_EXECUTABLE}"
            --inputdir=${CMAKE_CURRENT_SOURCE_DIR}
            --load-extension=oracle_fdw
            oracle_fdw oracle_gis oracle_import oracle_join
  )
endif()
