#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdlib>
#include <cerrno>
#include <csignal>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "TerminalPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

void free_cstring_array(char **arr) {
    if (arr == nullptr) return;
    for (int i = 0; arr[i] != nullptr; i++) {
        free(arr[i]);
    }
    free(arr);
}

// Close every FD above stderr so the child does not inherit sockets / asset FDs.
void close_extra_fds() {
    int max_fd = static_cast<int>(sysconf(_SC_OPEN_MAX));
    if (max_fd < 0 || max_fd > 1024) {
        max_fd = 1024;
    }
    for (int fd = 3; fd < max_fd; fd++) {
        close(fd);
    }
}

// Interactive "sane" settings: cooked input + echo + signals, CRLF output.
void apply_sane_termios(int slave_fd) {
    struct termios tio {};
    if (tcgetattr(slave_fd, &tio) != 0) {
        return;
    }

    // Start from a known baseline, then re-enable interactive defaults.
    cfmakeraw(&tio);

    tio.c_iflag |= ICRNL | IXON | IUTF8;
    tio.c_oflag |= OPOST | ONLCR;
    tio.c_lflag |= ECHO | ECHOE | ECHOK | ECHONL | ICANON | ISIG | IEXTEN;
    tio.c_cc[VEOF] = 4;    // Ctrl-D
    tio.c_cc[VEOL] = 0;
    tio.c_cc[VERASE] = 0x7f; // DEL
    tio.c_cc[VINTR] = 3;   // Ctrl-C
    tio.c_cc[VKILL] = 21;  // Ctrl-U
    tio.c_cc[VQUIT] = 28;  // Ctrl-\
    tio.c_cc[VSUSP] = 26;  // Ctrl-Z
    tio.c_cc[VSTART] = 17; // Ctrl-Q
    tio.c_cc[VSTOP] = 19;  // Ctrl-S
    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;

    tcsetattr(slave_fd, TCSANOW, &tio);
}

// read/write that retry on EINTR; write drains short writes.
ssize_t read_full(int fd, void *buf, size_t len) {
    for (;;) {
        ssize_t n = ::read(fd, buf, len);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        return n;
    }
}

ssize_t write_all(int fd, const void *buf, size_t len) {
    const auto *p = static_cast<const uint8_t *>(buf);
    size_t remaining = len;
    size_t total = 0;
    while (remaining > 0) {
        ssize_t n = ::write(fd, p, remaining);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return total > 0 ? static_cast<ssize_t>(total) : -1;
        }
        if (n == 0) {
            break;
        }
        p += n;
        remaining -= static_cast<size_t>(n);
        total += static_cast<size_t>(n);
    }
    return static_cast<ssize_t>(total);
}

int wait_status_to_code(int status) {
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        // Negative signal number distinguishes signal death from exit codes.
        return -WTERMSIG(status);
    }
    return -2;
}

} // namespace

extern "C" {

JNIEXPORT jintArray JNICALL
Java_cn_wty5_term_terminal_Pty_create(JNIEnv *env, jobject /*clazz*/,
                                      jstring cmd_str, jstring cwd_str,
                                      jobjectArray args_array, jobjectArray envp_array) {
    if (cmd_str == nullptr || cwd_str == nullptr || args_array == nullptr) {
        LOGE("create: null required argument");
        return nullptr;
    }

    // 1. Open master PTY (close-on-exec so the child does not keep a second master).
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        return nullptr;
    }

    if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(master_fd);
        return nullptr;
    }

    // Thread-safe ptsname.
    char slave_name[64];
    if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        LOGE("ptsname_r failed: %s", strerror(errno));
        close(master_fd);
        return nullptr;
    }

    const char *cmd = env->GetStringUTFChars(cmd_str, nullptr);
    const char *cwd = env->GetStringUTFChars(cwd_str, nullptr);
    if (cmd == nullptr || cwd == nullptr) {
        LOGE("create: GetStringUTFChars failed");
        if (cmd != nullptr) env->ReleaseStringUTFChars(cmd_str, cmd);
        if (cwd != nullptr) env->ReleaseStringUTFChars(cwd_str, cwd);
        close(master_fd);
        return nullptr;
    }

    const int argc = env->GetArrayLength(args_array);
    char **argv = static_cast<char **>(malloc(static_cast<size_t>(argc + 2) * sizeof(char *)));
    if (argv == nullptr) {
        env->ReleaseStringUTFChars(cmd_str, cmd);
        env->ReleaseStringUTFChars(cwd_str, cwd);
        close(master_fd);
        return nullptr;
    }
    argv[0] = strdup(cmd);
    for (int i = 0; i < argc; i++) {
        auto arg = reinterpret_cast<jstring>(env->GetObjectArrayElement(args_array, i));
        if (arg == nullptr) {
            argv[i + 1] = strdup("");
            continue;
        }
        const char *arg_utf = env->GetStringUTFChars(arg, nullptr);
        argv[i + 1] = strdup(arg_utf != nullptr ? arg_utf : "");
        if (arg_utf != nullptr) {
            env->ReleaseStringUTFChars(arg, arg_utf);
        }
        env->DeleteLocalRef(arg);
    }
    argv[argc + 1] = nullptr;

    const int envc = envp_array != nullptr ? env->GetArrayLength(envp_array) : 0;
    char **envp = static_cast<char **>(malloc(static_cast<size_t>(envc + 1) * sizeof(char *)));
    if (envp == nullptr) {
        free_cstring_array(argv);
        env->ReleaseStringUTFChars(cmd_str, cmd);
        env->ReleaseStringUTFChars(cwd_str, cwd);
        close(master_fd);
        return nullptr;
    }
    for (int i = 0; i < envc; i++) {
        auto env_item = reinterpret_cast<jstring>(env->GetObjectArrayElement(envp_array, i));
        if (env_item == nullptr) {
            envp[i] = strdup("");
            continue;
        }
        const char *env_utf = env->GetStringUTFChars(env_item, nullptr);
        envp[i] = strdup(env_utf != nullptr ? env_utf : "");
        if (env_utf != nullptr) {
            env->ReleaseStringUTFChars(env_item, env_utf);
        }
        env->DeleteLocalRef(env_item);
    }
    envp[envc] = nullptr;

    const pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        free_cstring_array(argv);
        free_cstring_array(envp);
        env->ReleaseStringUTFChars(cmd_str, cmd);
        env->ReleaseStringUTFChars(cwd_str, cwd);
        close(master_fd);
        return nullptr;
    }

    if (pid == 0) {
        // ---- Child ----
        // New session so we can become the controlling terminal's session leader.
        if (setsid() < 0) {
            _exit(126);
        }

        // Drop the inherited master; open a fresh slave as controlling TTY.
        close(master_fd);

        int slave_fd = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave_fd < 0) {
            _exit(126);
        }

        if (ioctl(slave_fd, TIOCSCTTY, 0) != 0) {
            // Non-fatal on some devices; continue with redirected FDs.
        }

        apply_sane_termios(slave_fd);

        if (dup2(slave_fd, STDIN_FILENO) < 0 ||
            dup2(slave_fd, STDOUT_FILENO) < 0 ||
            dup2(slave_fd, STDERR_FILENO) < 0) {
            _exit(126);
        }
        if (slave_fd > STDERR_FILENO) {
            close(slave_fd);
        }

        // Avoid leaking parent FDs (sockets, asset descriptors, etc.).
        close_extra_fds();

        // Restore default signal disposition for the interactive shell.
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTSTP, SIG_DFL);
        signal(SIGTTIN, SIG_DFL);
        signal(SIGTTOU, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);

        if (cwd != nullptr && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                // Fall back to / so the shell still starts.
                chdir("/");
            }
        }

        // Prefer a clean environment when the caller supplied one.
        if (envp != nullptr && envp[0] != nullptr) {
            execve(cmd, argv, envp);
        } else {
            execvp(cmd, argv);
        }

        // exec failed
        _exit(127);
    }

    // ---- Parent ----
    env->ReleaseStringUTFChars(cmd_str, cmd);
    env->ReleaseStringUTFChars(cwd_str, cwd);
    free_cstring_array(argv);
    free_cstring_array(envp);

    // Ensure CLOEXEC even if the open flag was ignored on older kernels.
    int flags = fcntl(master_fd, F_GETFD);
    if (flags >= 0) {
        fcntl(master_fd, F_SETFD, flags | FD_CLOEXEC);
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(master_fd);
        // Kill + reap so we never leak a zombie when JNI allocation fails.
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
        }
        return nullptr;
    }
    const jint temp[2] = {master_fd, static_cast<jint>(pid)};
    env->SetIntArrayRegion(result, 0, 2, temp);
    return result;
}

JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_read(JNIEnv *env, jobject /*clazz*/, jint fd, jbyteArray buffer) {
    if (fd < 0 || buffer == nullptr) {
        return -1;
    }
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (buf == nullptr) {
        return -1;
    }
    const jsize len = env->GetArrayLength(buffer);
    const ssize_t n = read_full(fd, buf, static_cast<size_t>(len));
    // 0 = commit changes back to Java array (mode 0).
    env->ReleaseByteArrayElements(buffer, buf, 0);
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_write(JNIEnv *env, jobject /*clazz*/, jint fd, jbyteArray buffer,
                                     jint offset, jint length) {
    if (fd < 0 || buffer == nullptr || offset < 0 || length < 0) {
        return -1;
    }
    const jsize arr_len = env->GetArrayLength(buffer);
    if (offset > arr_len || length > arr_len - offset) {
        return -1;
    }
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (buf == nullptr) {
        return -1;
    }
    const ssize_t n = write_all(fd, buf + offset, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(buffer, buf, JNI_ABORT);
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_cn_wty5_term_terminal_Pty_close(JNIEnv *env, jobject /*clazz*/, jint fd) {
    (void) env;
    if (fd >= 0) {
        // Unblock a concurrent blocking read in the reader thread.
        close(fd);
    }
}

JNIEXPORT void JNICALL
Java_cn_wty5_term_terminal_Pty_resize(JNIEnv *env, jobject /*clazz*/,
                                      jint fd, jint rows, jint cols) {
    (void) env;
    if (fd < 0 || rows <= 0 || cols <= 0) {
        return;
    }
    struct winsize sz {};
    sz.ws_row = static_cast<unsigned short>(rows);
    sz.ws_col = static_cast<unsigned short>(cols);
    sz.ws_xpixel = 0;
    sz.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &sz) != 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

/**
 * Wait for @pid.
 * @param hang  true  → block until the process exits
 *              false → WNOHANG (return -1 while still running)
 * @return exit status (0-255), -signal if killed by signal, -1 still running (non-hang only), -2 error
 */
JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_waitProcess(JNIEnv *env, jobject /*clazz*/,
                                           jint pid, jboolean hang) {
    (void) env;
    if (pid <= 0) {
        return -2;
    }
    int status = 0;
    for (;;) {
        const pid_t res = waitpid(pid, &status, hang ? 0 : WNOHANG);
        if (res == pid) {
            return wait_status_to_code(status);
        }
        if (res == 0) {
            // Still running (only possible with WNOHANG).
            return -1;
        }
        if (errno == EINTR) {
            continue;
        }
        // ECHILD: already reaped, or not our child.
        LOGE("waitpid(%d) failed: %s", pid, strerror(errno));
        return -2;
    }
}

/**
 * Signal a child. Tries the process group first (child called setsid),
 * then falls back to the single PID.
 * @return 0 on success, -1 on failure
 */
JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_killProcess(JNIEnv *env, jobject /*clazz*/,
                                           jint pid, jint sig) {
    (void) env;
    if (pid <= 0) {
        return -1;
    }
    // Negative PID = process group (session leader after setsid).
    if (kill(-pid, sig) == 0) {
        return 0;
    }
    if (kill(pid, sig) == 0) {
        return 0;
    }
    // ESRCH is fine — process already gone.
    if (errno == ESRCH) {
        return 0;
    }
    LOGE("kill(%d, %d) failed: %s", pid, sig, strerror(errno));
    return -1;
}

} // extern "C"
