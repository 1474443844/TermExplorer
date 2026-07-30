#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdlib>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "TerminalPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jintArray JNICALL
Java_cn_wty5_term_terminal_Pty_create(JNIEnv *env, jobject clazz,
                                      jstring cmd_str, jstring cwd_str,
                                      jobjectArray args_array, jobjectArray envp_array) {
    // 1. Open master PTY
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (master_fd < 0) {
        LOGE("posix_openpt failed");
        return nullptr;
    }

    if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
        LOGE("grantpt or unlockpt failed");
        close(master_fd);
        return nullptr;
    }

    char *slave_name = ptsname(master_fd);
    if (slave_name == nullptr) {
        LOGE("ptsname failed");
        close(master_fd);
        return nullptr;
    }

    // Prepare command, working directory, arguments, envs
    const char *cmd = env->GetStringUTFChars(cmd_str, nullptr);
    const char *cwd = env->GetStringUTFChars(cwd_str, nullptr);

    int argc = env->GetArrayLength(args_array);
    char **argv = (char **) malloc((argc + 2) * sizeof(char *));
    argv[0] = strdup(cmd);
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args_array, i);
        const char *arg_utf = env->GetStringUTFChars(arg, nullptr);
        argv[i + 1] = strdup(arg_utf);
        env->ReleaseStringUTFChars(arg, arg_utf);
    }
    argv[argc + 1] = nullptr;

    int envc = envp_array != nullptr ? env->GetArrayLength(envp_array) : 0;
    char **envp = (char **) malloc((envc + 1) * sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring env_item = (jstring) env->GetObjectArrayElement(envp_array, i);
        const char *env_utf = env->GetStringUTFChars(env_item, nullptr);
        envp[i] = strdup(env_utf);
        env->ReleaseStringUTFChars(env_item, env_utf);
    }
    envp[envc] = nullptr;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed");
        close(master_fd);
        // Clean up mallocs
        for (int i = 0; argv[i] != nullptr; i++) free(argv[i]);
        free(argv);
        for (int i = 0; envp[i] != nullptr; i++) free(envp[i]);
        free(envp);
        env->ReleaseStringUTFChars(cmd_str, cmd);
        env->ReleaseStringUTFChars(cwd_str, cwd);
        return nullptr;
    } else if (pid == 0) {
        // Child Process
        // Create new session, make us group leader
        setsid();

        // Open the slave side
        int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) {
            exit(1);
        }

        // Set as controlling terminal
        ioctl(slave_fd, TIOCSCTTY, 0);

        // Termios settings - turn on basic raw/sane terminal modes if needed
        struct termios tio;
        if (tcgetattr(slave_fd, &tio) == 0) {
            cfmakeraw(&tio);
            tio.c_lflag |= (ECHO | ICANON | ISIG | IEXTEN);
            tio.c_iflag |= (ICRNL | IXON);
            tio.c_oflag |= (OPOST | ONLCR);
            tcsetattr(slave_fd, TCSANOW, &tio);
        }

        // Redirect stdin, stdout, stderr
        dup2(slave_fd, 0);
        dup2(slave_fd, 1);
        dup2(slave_fd, 2);

        // Close slave_fd and master_fd as they are duplicated or unused in child
        if (slave_fd > 2) {
            close(slave_fd);
        }
        close(master_fd);

        // Change directory
        if (cwd != nullptr && strlen(cwd) > 0) {
            chdir(cwd);
        }

        // Execute
        if (envp != nullptr && envp[0] != nullptr) {
            execve(cmd, argv, envp);
        } else {
            execvp(cmd, argv);
        }

        // If exec fails
        exit(1);
    }

    // Parent Process
    // Release resources
    env->ReleaseStringUTFChars(cmd_str, cmd);
    env->ReleaseStringUTFChars(cwd_str, cwd);
    // Free argv and envp
    for (int i = 0; argv[i] != nullptr; i++) free(argv[i]);
    free(argv);
    for (int i = 0; envp[i] != nullptr; i++) free(envp[i]);
    free(envp);

    // Return [master_fd, pid]
    jintArray result = env->NewIntArray(2);
    jint temp[2] = {master_fd, (jint) pid};
    env->SetIntArrayRegion(result, 0, 2, temp);
    return result;
}

JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_read(JNIEnv *env, jobject clazz, jint fd, jbyteArray buffer) {
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    jsize len = env->GetArrayLength(buffer);

    int read_bytes = read(fd, buf, len);

    env->ReleaseByteArrayElements(buffer, buf, 0);
    return read_bytes;
}

JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_write(JNIEnv *env, jobject clazz, jint fd, jbyteArray buffer,
                                     jint offset, jint length) {
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);

    int written = write(fd, buf + offset, length);

    env->ReleaseByteArrayElements(buffer, buf, JNI_ABORT);
    return written;
}

JNIEXPORT void JNICALL
Java_cn_wty5_term_terminal_Pty_close(JNIEnv *env, jobject clazz, jint fd) {
    close(fd);
}

JNIEXPORT void JNICALL
Java_cn_wty5_term_terminal_Pty_resize(JNIEnv *env, jobject clazz, jint fd, jint rows, jint cols) {
    struct winsize sz;
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    sz.ws_xpixel = 0;
    sz.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_cn_wty5_term_terminal_Pty_waitProcess(JNIEnv *env, jobject clazz, jint pid) {
    int status = 0;
    pid_t res = waitpid(pid, &status, WNOHANG);
    if (res == pid) {
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        }
        if (WIFSIGNALED(status)) {
            return -WTERMSIG(status);
        }
    } else if (res == 0) {
        // Still running
        return -1;
    }
    return -2; // Error / Waitpid failed
}

}
