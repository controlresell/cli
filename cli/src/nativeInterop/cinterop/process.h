#ifndef CONTROLRESELL_PROCESS_H
#define CONTROLRESELL_PROCESS_H
#include <spawn.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <fcntl.h>

#ifdef __APPLE__
#include <crt_externs.h>
#include <mach-o/dyld.h>
#endif

static inline int controlresell_executable(char *buffer, uint32_t capacity) {
#ifdef __APPLE__
    return _NSGetExecutablePath(buffer, &capacity);
#else
    ssize_t length = readlink("/proc/self/exe", buffer, capacity - 1);
    if (length < 0 || length >= capacity - 1) return -1;
    buffer[length] = 0;
    return 0;
#endif
}
static inline char **controlresell_environment(void) {
#ifdef __APPLE__
    return *_NSGetEnviron();
#else
    extern char **environ;
    return environ;
#endif
}

/* No shell or fork inside the multithreaded Kotlin runtime; inherit the caller's terminal. */
static inline int controlresell_spawn(const char *command, char *const argv[], char *const envp[], int *child, int quiet) {
    posix_spawnattr_t attributes;
    int result = posix_spawnattr_init(&attributes);
    if (result != 0) return result;
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGINT);
    sigaddset(&defaults, SIGTERM);
    sigaddset(&defaults, SIGHUP);
    sigaddset(&defaults, SIGQUIT);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGDEF);
    posix_spawn_file_actions_t actions;
    result = posix_spawn_file_actions_init(&actions);
    if (result != 0) { posix_spawnattr_destroy(&attributes); return result; }
    if (quiet) {
        result = posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, "/dev/null", O_WRONLY, 0);
        if (result == 0) result = posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
        if (result != 0) { posix_spawn_file_actions_destroy(&actions); posix_spawnattr_destroy(&attributes); return result; }
    }
    pid_t pid;
    result = posix_spawnp(&pid, command, &actions, &attributes, argv, envp);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attributes);
    if (result == 0) *child = (int)pid;
    return result;
}

/* Poll only this exact owned child. A reaped PID is never signalled. */
static inline int controlresell_poll(int child, int *code) {
    int status;
    pid_t result = waitpid((pid_t)child, &status, WNOHANG);
    if (result == 0 || (result < 0 && errno == EINTR)) return 0;
    if (result < 0) return -1;
    if (WIFEXITED(status)) *code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) *code = 128 + WTERMSIG(status);
    else return 0;
    return 1;
}
static inline void controlresell_terminate(int child, int force) { kill((pid_t)child, force ? SIGKILL : SIGTERM); }
#endif
