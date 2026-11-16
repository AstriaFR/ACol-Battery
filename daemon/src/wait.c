#define _GNU_SOURCE
#include <jni.h>
#include <sys/timerfd.h>
#include <sys/socket.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <linux/netlink.h>
#include <poll.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <limits.h>

enum {
    WAIT_TIMER=1, WAIT_POWER=2, WAIT_SIGNAL=4, WAIT_MODULE=8, WAIT_SUPPLIES_CHANGED=16,
    WAIT_POWER_LOST=32, WAIT_SIGNAL_LOST=64, WAIT_MODULE_LOST=128, WAIT_ALARM_LOST=256, WAIT_TIMER_LOST=512
};

static int timer=-1, alarm_fd=-1, events=-1, notify=-1, module_events=-1, wake_hold=0;
static pthread_mutex_t notify_lock=PTHREAD_MUTEX_INITIALIZER;
static char wait_errors[4096];
static void wait_error(const char *operation,int number) {
    size_t used=strlen(wait_errors);
    if(used<sizeof(wait_errors)-1)snprintf(wait_errors+used,sizeof(wait_errors)-used,"%s: errno=%d (%s)\n",operation,number,strerror(number));
}
static int write_node(const char *path,const char *value) {
    int fd=open(path,O_WRONLY|O_CLOEXEC);
    if(fd<0) return 0;
    size_t n=strlen(value);ssize_t written=write(fd,value,n);int saved=written<0?errno:EIO;int ok=written==(ssize_t)n;
    close(fd);if(!ok)errno=saved;return ok;
}
static void hold(void) {
    wake_hold=write_node("/sys/power/wake_lock","aco_battery_app 10000000000");
}
JNIEXPORT jint JNICALL Java_cn_astriafr_acobattery_daemon_Main_waitCapabilities(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;
    return (alarm_fd>=0?1:0)|(events>=0?2:0)|(wake_hold?4:0)|(timer>=0||alarm_fd>=0?8:0)|(notify>=0?16:0);
}
JNIEXPORT jint JNICALL Java_cn_astriafr_acobattery_daemon_Main_initWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;
    wait_errors[0]=0;
    timer=timerfd_create(CLOCK_BOOTTIME,TFD_CLOEXEC|TFD_NONBLOCK);
    if(timer<0)wait_error("timerfd_create(CLOCK_BOOTTIME)",errno);
    alarm_fd=timerfd_create(CLOCK_BOOTTIME_ALARM,TFD_CLOEXEC|TFD_NONBLOCK);
    if(alarm_fd<0)wait_error("timerfd_create(CLOCK_BOOTTIME_ALARM)",errno);
    else {
        // Verify CAP_WAKE_ALARM without leaving an alarm armed during initialization.
        struct itimerspec test={0};
        if(timerfd_settime(alarm_fd,0,&test,NULL)<0){wait_error("timerfd_settime(CLOCK_BOOTTIME_ALARM)",errno);close(alarm_fd);alarm_fd=-1;}
    }
    notify=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    if(notify<0)wait_error("eventfd",errno);
    events=socket(AF_NETLINK,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_KOBJECT_UEVENT);
    if(events<0)wait_error("socket(NETLINK_KOBJECT_UEVENT)",errno);
    if(events>=0) {
        struct sockaddr_nl address={0};address.nl_family=AF_NETLINK;address.nl_pid=getpid();address.nl_groups=1;
        if(bind(events,(struct sockaddr*)&address,sizeof(address))<0){wait_error("bind(NETLINK_KOBJECT_UEVENT)",errno);close(events);events=-1;}
    }
    hold();
    if(!wake_hold)wait_error("write(/sys/power/wake_lock)",errno);
    return Java_cn_astriafr_acobattery_daemon_Main_waitCapabilities(env,cls);
}
JNIEXPORT jstring JNICALL Java_cn_astriafr_acobattery_daemon_Main_waitDiagnostics(JNIEnv *env,jclass cls) {
    (void)cls;return (*env)->NewStringUTF(env,wait_errors);
}
JNIEXPORT void JNICALL Java_cn_astriafr_acobattery_daemon_Main_interruptWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;uint64_t count=1;
    pthread_mutex_lock(&notify_lock);
    if(notify>=0) {ssize_t result;do {result=write(notify,&count,sizeof(count));} while(result<0&&errno==EINTR);}
    pthread_mutex_unlock(&notify_lock);
}
JNIEXPORT jboolean JNICALL Java_cn_astriafr_acobattery_daemon_Main_watchModule(JNIEnv *env,jclass cls,jstring path) {
    (void)cls;
    const char *directory=(*env)->GetStringUTFChars(env,path,NULL);
    if(!directory)return JNI_FALSE;
    module_events=inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
    if(module_events<0)wait_error("inotify_init1",errno);
    if(module_events>=0&&inotify_add_watch(module_events,directory,IN_CREATE|IN_MOVED_TO|IN_ATTRIB|IN_DELETE_SELF|IN_MOVE_SELF)<0) {
        wait_error("inotify_add_watch",errno);
        close(module_events);module_events=-1;
    }
    (*env)->ReleaseStringUTFChars(env,path,directory);
    return module_events>=0?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_cn_astriafr_acobattery_daemon_Main_releaseWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;write_node("/sys/power/wake_unlock","aco_battery_app");
}
static void disable_fd(int *fd) {
    close(*fd);
    *fd=-1;
}

static int arm_timer(int *fd,int64_t deadline,int lost) {
    if(*fd<0)return 0;
    struct itimerspec spec={0};
    if(deadline!=INT64_MAX){
        if(deadline<1)deadline=1;
        spec.it_value.tv_sec=deadline/1000;
        spec.it_value.tv_nsec=(deadline%1000)*1000000;
    }
    if(timerfd_settime(*fd,TFD_TIMER_ABSTIME,&spec,NULL)==0)return 0;
    disable_fd(fd);
    return lost;
}

static int remaining_timeout(int64_t deadline) {
    if(deadline==INT64_MAX)return -1;
    struct timespec now;
    if(clock_gettime(CLOCK_BOOTTIME,&now)<0)return 1000;
    int64_t remaining=deadline-((int64_t)now.tv_sec*1000+now.tv_nsec/1000000);
    return remaining>INT_MAX?INT_MAX:remaining>0?(int)remaining:0;
}

static int power_event(const char *message,size_t length) {
    int power=0,topology=0;
    for(size_t i=0;i<length;){
        const char *field=message+i;
        size_t size=strnlen(field,length-i);
        if(size==length-i)break;
        if(!strcmp(field,"SUBSYSTEM=power_supply")||!strcmp(field,"SUBSYSTEM=oplus_chg")||
            (!strncmp(field,"DEVPATH=",8)&&(strstr(field,"/power_supply/")||strstr(field,"/oplus_chg/"))))power=1;
        if(!strcmp(field,"ACTION=add")||!strcmp(field,"ACTION=remove")||!strcmp(field,"ACTION=move"))topology=1;
        i+=size+1;
    }
    return power?(WAIT_POWER|(topology?WAIT_SUPPLIES_CHANGED:0)):0;
}

static int power_events(short ready) {
    if(ready&(POLLHUP|POLLNVAL)){
        disable_fd(&events);
        return WAIT_POWER_LOST|WAIT_POWER|WAIT_SUPPLIES_CHANGED;
    }
    int result=0;
    if(ready&POLLERR){
        int error=0;
        socklen_t size=sizeof(error);
        if(getsockopt(events,SOL_SOCKET,SO_ERROR,&error,&size)<0||error!=ENOBUFS){
            disable_fd(&events);
            return WAIT_POWER_LOST|WAIT_POWER|WAIT_SUPPLIES_CHANGED;
        }
        // A receive overflow may have lost transitions or directory changes.
        result=WAIT_POWER|WAIT_SUPPLIES_CHANGED;
    }
    if(ready&POLLIN){
        char message[8192];
        // Drain a burst as one event without starving timer and stop signals.
        for(int i=0;i<32;i++){
            ssize_t length=recv(events,message,sizeof(message),0);
            if(length>0)result|=power_event(message,(size_t)length);
            else if(length<0&&errno==ENOBUFS)result|=WAIT_POWER|WAIT_SUPPLIES_CHANGED;
            else break;
        }
    }
    return result;
}

static int module_changes(short ready) {
    if(ready&(POLLERR|POLLHUP|POLLNVAL)){
        disable_fd(&module_events);
        return WAIT_MODULE_LOST;
    }
    if(!(ready&POLLIN))return 0;
    int result=0;
    char buffer[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
    ssize_t length=read(module_events,buffer,sizeof(buffer));
    for(ssize_t offset=0;offset+(ssize_t)sizeof(struct inotify_event)<=length;){
        const struct inotify_event *change=(const struct inotify_event *)(buffer+offset);
        if(change->mask&(IN_IGNORED|IN_DELETE_SELF|IN_MOVE_SELF)){
            disable_fd(&module_events);
            return result|WAIT_MODULE_LOST;
        }
        if(change->mask&IN_Q_OVERFLOW)result|=WAIT_MODULE;
        if(change->len&&!strcmp(change->name,"screen.event"))result|=WAIT_MODULE;
        offset+=(ssize_t)sizeof(*change)+change->len;
    }
    return result;
}

static int consume_counter(int *fd,short ready,int event,int lost) {
    if(ready&(POLLERR|POLLHUP|POLLNVAL)){
        disable_fd(fd);
        return lost;
    }
    if(ready&POLLIN){uint64_t count; if(read(*fd,&count,sizeof(count))==sizeof(count))return event;}
    return 0;
}

JNIEXPORT jint JNICALL Java_cn_astriafr_acobattery_daemon_Main_waitEvent(JNIEnv *env,jclass cls,jlong due,jlong wake_due,jboolean watch_power) {
    (void)env;(void)cls;
    int result=arm_timer(&alarm_fd,wake_due,WAIT_ALARM_LOST);
    int64_t normal_due=due<wake_due?due:alarm_fd>=0?INT64_MAX:wake_due;
    result|=arm_timer(&timer,normal_due,WAIT_TIMER_LOST);
    if(result)return result;
    struct pollfd fds[]={{timer,POLLIN,0},{alarm_fd,POLLIN,0},{watch_power?events:-1,POLLIN,0},{notify,POLLIN,0},{module_events,POLLIN,0}};
    for(;;){
        int n=poll(fds,sizeof(fds)/sizeof(fds[0]),timer>=0?-1:remaining_timeout(normal_due));
        if(n<0){if(errno==EINTR)continue;return -1;}
        if(n==0){hold();return WAIT_TIMER;}
        result=consume_counter(&timer,fds[0].revents,WAIT_TIMER,WAIT_TIMER_LOST)|
            consume_counter(&alarm_fd,fds[1].revents,WAIT_TIMER,WAIT_ALARM_LOST);
        result|=power_events(fds[2].revents);
        if(fds[3].revents){
            pthread_mutex_lock(&notify_lock);
            result|=consume_counter(&notify,fds[3].revents,WAIT_SIGNAL,WAIT_SIGNAL_LOST);
            pthread_mutex_unlock(&notify_lock);
        }
        result|=module_changes(fds[4].revents);
        if(result){hold();return result;}
    }
}
