#define _GNU_SOURCE
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void move_group(const char *path) {
    int fd=open(path,O_WRONLY|O_CLOEXEC);if(fd<0)return;
    char text[32];int n=snprintf(text,sizeof(text),"%ld",(long)getpid());
    if(write(fd,text,(size_t)n)<0){}close(fd);
}
int main(int argc,char **argv) {
    if(getuid()!=0||argc!=2||(strcmp(argv[1],"/data/adb/aco_battery_app/runtime/service.sh")&&strcmp(argv[1],"/data/adb/aco_battery_app/runtime/transfer.sh")))return 1;
    pid_t pid=fork();if(pid<0)return 1;if(pid>0)return 0;
    if(setsid()<0)_exit(1);
    pid=fork();if(pid<0)_exit(1);if(pid>0)_exit(0);
    chdir("/");umask(077);
    int input=open("/dev/null",O_RDONLY),output=open("/data/adb/aco_battery_app/supervisor.log",O_WRONLY|O_CREAT|O_APPEND,0600);
    if(input<0||output<0)_exit(1);
    dup2(input,0);dup2(output,1);dup2(output,2);close(input);close(output);
    // Detach from the UI's freezer/process cgroup as well as its session.
    move_group("/sys/fs/cgroup/cgroup.procs");move_group("/acct/cgroup.procs");
    execl("/system/bin/sh","sh",argv[1],(char *)NULL);_exit(1);
}
