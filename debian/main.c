/*****************************************************************************
 * touch_confidence.c (精简版)
 *   - 左侧滚动显示 minimodem 管道命令的输出
 *   - 右侧一列: "+" 按钮 / 当前置信度(-c 参数) / "-" 按钮
 *   - 触摸按钮调整置信度(防抖后合并提交),自动重启 arecord|sox|minimodem 管道
 *   - 启动时全刷一次,之后全部用局刷(实测发现周期性全刷反而更容易留残影)
 *
 * 放在 c/examples/ 目录下。编译(在仓库 c/ 目录下执行):
 *
 *   gcc -g -O2 -Wall -D USE_LGPIO_LIB -c examples/touch_confidence.c -o bin/touch_confidence.o \
 *       -I ./lib/Config -I ./lib/Driver -I ./lib/EPD -I ./lib/GUI
 *   gcc -g -O2 -Wall -D USE_LGPIO_LIB \
 *       bin/touch_confidence.o bin/DEV_Config.o bin/GT1151.o bin/EPD_2in13_V4.o \
 *       bin/GUI_Paint.o bin/font12.o bin/font20.o bin/font24.o \
 *       -o touch_confidence -llgpio -lm -lpthread
 *   sudo ./touch_confidence
 *****************************************************************************/
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <pthread.h>
#include <time.h>
#include <sys/types.h>
#include <sys/wait.h>

#include "DEV_Config.h"
#include "GUI_Paint.h"
#include "../lib/Fonts/fonts.h"
#include "EPD_2in13_V4.h"
#include "GT1151.h"

extern GT1151_Dev Dev_Now;
extern int IIC_Address;

/* ================= 可调参数 ================= */
#define CONF_MIN   1.0
#define CONF_MAX   5.0
#define CONF_STEP  0.1

#define TOUCH_DEBOUNCE_MS    300  /* 一次按压(哪怕信号抖动)这段时间内只算一次 */
#define CONF_COMMIT_DELAY_MS 800  /* 连续点击停下来这么久后才真正提交(重启管道+刷屏) */
#define OUTPUT_REFRESH_MS    1500 /* 输出区最多这么久刷新一次,避免局刷过于频繁 */

/* 屏幕实际可用区域(用 border_test.c 实测校准得出) */
#define SCR_X0  1
#define SCR_Y0  1
#define SCR_X1  250
#define SCR_Y1  122
#define SCR_W   (SCR_X1 - SCR_X0)
#define SCR_H   (SCR_Y1 - SCR_Y0)

/* 右侧控制列占屏幕宽度的比例, 布局其余部分都由此推导, 换屏幕/换比例都不用逐个改坐标 */
#define CTRL_RATIO   0.27
#define CTRL_W       ((int)(SCR_W * CTRL_RATIO))
#define GAP          4

#define DIVIDER_X    (SCR_X1 - CTRL_W)
#define OUT_X0       SCR_X0
#define OUT_Y0       SCR_Y0
#define OUT_X1       DIVIDER_X
#define OUT_Y1       SCR_Y1
#define COL_X0       DIVIDER_X
#define COL_X1       SCR_X1

#define BTN_H        ((SCR_H - GAP * 2) / 3)
#define BTN_PLUS_Y0  SCR_Y0
#define BTN_PLUS_Y1  (BTN_PLUS_Y0 + BTN_H)
#define VAL_Y0       (BTN_PLUS_Y1 + GAP)
#define VAL_Y1       (VAL_Y0 + BTN_H)
#define BTN_MINUS_Y0 (VAL_Y1 + GAP)
#define BTN_MINUS_Y1 SCR_Y1

#define LINE_MAXLEN  ((OUT_X1 - OUT_X0 - 4) / 7)  /* Font12 约7px/字, 减4是留给左边距+右侧安全余量 */
#define OUT_LINES    ((OUT_Y1 - OUT_Y0) / 13)      /* 每行占13px高 */

/* ================= 全局状态 ================= */
static volatile int flag_t = 1;
static volatile double g_confidence = 2.4;
static UBYTE *g_img = NULL;
static pthread_t g_irq_tid;

void *pthread_irq(void *arg) {
    (void)arg;
    while (flag_t) {
        Dev_Now.Touch = (DEV_Digital_Read(INT) == 0) ? 1 : 0;
        DEV_Delay_ms(1);
    }
    pthread_exit(NULL);
}

/* -------- 子进程管道: arecord | sox | minimodem -------- */
typedef struct { pid_t pid; int fd; } Pipeline;
static Pipeline g_pipe = { .pid = -1, .fd = -1 };

static int start_pipeline(Pipeline *p, double confidence) {
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
        "arecord -D \"bluealsa:DEV=AC:37:43:AD:05:0B,PROFILE=a2dp\" "
        "-f S16_LE -c 2 -r 44100 -t raw 2>/dev/null | "
        "sox -r 44100 -e signed -b 16 -c 2 -t raw - -c 1 -t wav - 2>/dev/null | "
        "minimodem --rx -f - --quiet -c %.1f 1200 2>/dev/null", confidence);

    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    if (pid == 0) {
        setsid();
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[0]); close(pipefd[1]);
        execl("/bin/bash", "bash", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);
    fcntl(pipefd[0], F_SETFL, fcntl(pipefd[0], F_GETFL, 0) | O_NONBLOCK);
    p->pid = pid; p->fd = pipefd[0];
    printf("[pipeline] started, confidence=%.1f, pid=%d\n", confidence, (int)pid);
    return 0;
}

static void stop_pipeline(Pipeline *p) {
    if (p->pid > 0) {
        killpg(p->pid, SIGTERM);  /* 子进程 setsid() 过, pgid == pid */
        waitpid(p->pid, NULL, 0);
        close(p->fd);
        p->pid = -1; p->fd = -1;
        printf("[pipeline] stopped\n");
    }
}

/* -------- 滚动文本缓冲区 -------- */
static char g_lines[OUT_LINES][LINE_MAXLEN + 1];
static char g_curline[LINE_MAXLEN + 1];
static int  g_curlen = 0;
static pthread_mutex_t g_lines_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_lines_dirty = 0;

static void push_line(const char *line) {
    pthread_mutex_lock(&g_lines_lock);
    for (int i = 0; i < OUT_LINES - 1; i++) memcpy(g_lines[i], g_lines[i + 1], sizeof(g_lines[i]));
    strncpy(g_lines[OUT_LINES - 1], line, LINE_MAXLEN);
    g_lines[OUT_LINES - 1][LINE_MAXLEN] = '\0';
    g_lines_dirty = 1;
    pthread_mutex_unlock(&g_lines_lock);
}

static void feed_bytes(const char *buf, int n) {
    for (int i = 0; i < n; i++) {
        char c = buf[i];
        /* minimodem 非tty输出时会把真实\r\n转成字面的反斜杠+r/n这4个可见字符,一并当断行处理 */
        if (c == '\\' && i + 1 < n && (buf[i + 1] == 'r' || buf[i + 1] == 'n')) {
            if (g_curlen) { g_curline[g_curlen] = '\0'; push_line(g_curline); g_curlen = 0; }
            i++;
            continue;
        }
        if (c == '\n' || c == '\r') {
            if (g_curlen) { g_curline[g_curlen] = '\0'; push_line(g_curline); g_curlen = 0; }
        } else if (c >= 0x20 && c < 0x7F) {
            if (g_curlen >= LINE_MAXLEN) { g_curline[g_curlen] = '\0'; push_line(g_curline); g_curlen = 0; }
            g_curline[g_curlen++] = c;
        }
    }
}

/* -------- 触摸坐标换算(已实测校准) -------- */
static inline int touch_to_lx(int nx, int ny) { (void)nx; return (SCR_X1 - 1) - ny; }
static inline int touch_to_ly(int nx, int ny) { (void)ny; return nx; }

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* -------- 绘制(注意 Paint_DrawString_EN 的颜色参数顺序是 背景色,前景色) -------- */
static void draw_static_ui(void) {
    Paint_DrawRectangle(SCR_X0, SCR_Y0, SCR_X1, SCR_Y1, BLACK, DOT_PIXEL_1X1, DRAW_FILL_EMPTY);
    Paint_DrawLine(DIVIDER_X, SCR_Y0, DIVIDER_X, SCR_Y1, BLACK, DOT_PIXEL_1X1, LINE_STYLE_SOLID);

    Paint_DrawRectangle(COL_X0, BTN_PLUS_Y0, COL_X1, BTN_PLUS_Y1, BLACK, DOT_PIXEL_1X1, DRAW_FILL_FULL);
    Paint_DrawString_EN(COL_X0 + (CTRL_W - 17) / 2, BTN_PLUS_Y0 + (BTN_H - 24) / 2, "+", &Font24, BLACK, WHITE);

    Paint_DrawRectangle(COL_X0, BTN_MINUS_Y0, COL_X1, BTN_MINUS_Y1, BLACK, DOT_PIXEL_1X1, DRAW_FILL_FULL);
    Paint_DrawString_EN(COL_X0 + (CTRL_W - 17) / 2, BTN_MINUS_Y0 + (BTN_H - 24) / 2, "-", &Font24, BLACK, WHITE);
}

static void redraw_confidence(void) {
    /* 左右边缘都内缩1px,避免和分隔线(左)、外层边框(右)重合被一起擦掉 */
    Paint_ClearWindows(COL_X0 + 1, VAL_Y0, COL_X1 - 1, VAL_Y1, WHITE);
    char buf[8];
    snprintf(buf, sizeof(buf), "%.1f", g_confidence);
    int tw = 14 * (int)strlen(buf); /* Font20 约14px/字 */
    Paint_DrawString_EN(COL_X0 + (CTRL_W - tw) / 2, VAL_Y0 + (BTN_H - 20) / 2, buf, &Font20, WHITE, BLACK);
}

static void redraw_output(void) {
    pthread_mutex_lock(&g_lines_lock);
    /* 上/左/下三边都内缩1px,避免和外层边框线重合被一起擦掉 */
    Paint_ClearWindows(OUT_X0 + 1, OUT_Y0 + 1, OUT_X1 - 1, OUT_Y1 - 1, WHITE);
    for (int i = 0; i < OUT_LINES; i++)
        if (g_lines[i][0])
            Paint_DrawString_EN(OUT_X0 + 2, OUT_Y0 + 2 + i * 13, g_lines[i], &Font12, WHITE, BLACK);
    g_lines_dirty = 0;
    pthread_mutex_unlock(&g_lines_lock);
}

/* -------- 按钮定义: 用数组代替重复的 if/else -------- */
typedef struct { int y0, y1; double delta; } Btn;
static const Btn g_btns[2] = {
    { BTN_PLUS_Y0,  BTN_PLUS_Y1,  +CONF_STEP },
    { BTN_MINUS_Y0, BTN_MINUS_Y1, -CONF_STEP },
};

static long long g_last_output_ms = 0, g_last_trigger_ms = 0, g_last_button_ms = 0;
static volatile int g_confidence_pending = 0;

static void restart_pipeline(void) {
    stop_pipeline(&g_pipe);
    /* 不清空文本: 整块清空是"大面积突变",局刷刷不干净会留残影;
       保留旧文本让新数据自然滚动替换,增量小,局刷效果好(实测确认) */
    start_pipeline(&g_pipe, g_confidence);
}

static void cleanup_and_exit(int code) {
    flag_t = 0;
    pthread_join(g_irq_tid, NULL);
    stop_pipeline(&g_pipe);
    EPD_2in13_V4_Sleep();
    DEV_Delay_ms(500);
    DEV_ModuleExit();
    exit(code);
}

static void on_sigint(int signo) {
    (void)signo;
    printf("\nCtrl+C received, exiting...\n");
    cleanup_and_exit(0);
}

int main(void) {
    IIC_Address = 0x14;
    signal(SIGINT, on_sigint);

    DEV_ModuleInit();
    pthread_create(&g_irq_tid, NULL, pthread_irq, NULL);

    EPD_2in13_V4_Init(EPD_2IN13_V4_FULL);
    EPD_2in13_V4_Clear();
    GT_Init();
    DEV_Delay_ms(100);

    UWORD imgsize = ((EPD_2in13_V4_WIDTH % 8 == 0) ? (EPD_2in13_V4_WIDTH / 8) : (EPD_2in13_V4_WIDTH / 8 + 1)) * EPD_2in13_V4_HEIGHT;
    g_img = (UBYTE *)malloc(imgsize);
    if (!g_img) { printf("malloc failed\n"); return -1; }

    Paint_NewImage(g_img, EPD_2in13_V4_WIDTH, EPD_2in13_V4_HEIGHT, 90, WHITE); /* Rotate=90: 250x122 横屏逻辑坐标 */
    Paint_SelectImage(g_img);
    Paint_Clear(WHITE);

    draw_static_ui();
    redraw_confidence();
    redraw_output();

    EPD_2in13_V4_Display(g_img);
    EPD_2in13_V4_Init(EPD_2IN13_V4_PART);
    EPD_2in13_V4_Display_Partial_Wait(g_img);

    start_pipeline(&g_pipe, g_confidence);

    char rbuf[256];
    while (1) {
        int changed = 0;

        /* 1. 读取子进程输出(非阻塞) */
        if (g_pipe.fd >= 0) {
            ssize_t n;
            while ((n = read(g_pipe.fd, rbuf, sizeof(rbuf))) > 0) feed_bytes(rbuf, (int)n);
        }
        if (g_lines_dirty) {
            long long t = now_ms();
            if (t - g_last_output_ms >= OUTPUT_REFRESH_MS) {
                redraw_output();
                g_last_output_ms = t;
                changed = 1;
            }
        }

        /* 2. 触摸: 用固定时间窗口去抖(GT1151的INT在同一次按压中会按每次上报脉冲一次,
              电平边沿并不可靠,时间窗口去抖是嵌入式领域处理这类输入的标准做法) */
        GT_Scan();
        if (Dev_Now.TouchpointFlag) {
            Dev_Now.TouchpointFlag = 0;
            long long t = now_ms();
            if (t - g_last_trigger_ms >= TOUCH_DEBOUNCE_MS) {
                int lx = touch_to_lx(Dev_Now.X[0], Dev_Now.Y[0]);
                int ly = touch_to_ly(Dev_Now.X[0], Dev_Now.Y[0]);
                printf("[touch] logical=(%d,%d)\n", lx, ly);
                if (lx >= COL_X0 && lx <= COL_X1) {
                    for (int i = 0; i < 2; i++) {
                        if (ly >= g_btns[i].y0 && ly <= g_btns[i].y1) {
                            g_confidence += g_btns[i].delta;
                            if (g_confidence > CONF_MAX) g_confidence = CONF_MAX;
                            if (g_confidence < CONF_MIN) g_confidence = CONF_MIN;
                            printf("confidence -> %.1f (pending)\n", g_confidence);
                            redraw_confidence();
                            g_confidence_pending = 1;
                            g_last_button_ms = t;
                            g_last_trigger_ms = t;
                            break;
                        }
                    }
                }
            }
        }

        /* 2.5 防抖提交: 停止点击 CONF_COMMIT_DELAY_MS 毫秒后才真正重启管道+刷新一次 */
        if (g_confidence_pending && (now_ms() - g_last_button_ms >= CONF_COMMIT_DELAY_MS)) {
            printf("confidence committed -> %.1f\n", g_confidence);
            restart_pipeline();
            EPD_2in13_V4_Display_Partial(g_img); /* 文本没被清空,只需推送置信度改动 */
            g_confidence_pending = 0;
            changed = 0; /* 上面已经刷新过了 */
        }

        if (changed) EPD_2in13_V4_Display_Partial(g_img);

        usleep(50 * 1000);
    }
}
