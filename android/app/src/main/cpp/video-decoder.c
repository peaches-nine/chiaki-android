// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <string.h>
#include <time.h>

#define INPUT_BUFFER_TIMEOUT_MS 10

static void *android_chiaki_video_decoder_output_thread_func(void *user);

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec)
{
    decoder->log = log;
    decoder->codec = NULL;
    decoder->timestamp_cur = 0;
    decoder->target_width = target_width;
    decoder->target_height = target_height;
    decoder->target_codec = codec;
    decoder->shutdown_output = false;
    return chiaki_mutex_init(&decoder->codec_mutex, false);
}

// 存储每帧输入时间的结构体
typedef struct {
    struct timespec input_time; // 输入帧的时间戳
} FrameTimestamp;

FrameTimestamp *frame_input_time = NULL;  // 动态分配内存存储每帧的时间戳
uint64_t frame_index = 0;  // 当前帧的索引
uint64_t frames_decoded_in_last_second = 0; // 过去1秒内解码的帧数
double total_decode_latency = 0.0; // 累积解码延迟
struct timespec last_fps_time = {0}; // 上一次计算FPS的时间

static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
    chiaki_mutex_lock(&decoder->codec_mutex);
    decoder->shutdown_output = true;
    ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
    if(codec_buf_index >= 0)
    {
        CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
        AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        AMediaCodec_stop(decoder->codec);
        chiaki_mutex_unlock(&decoder->codec_mutex);
        chiaki_thread_join(&decoder->output_thread, NULL);
    }
    else
    {
        CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
        AMediaCodec_stop(decoder->codec);
        chiaki_mutex_unlock(&decoder->codec_mutex);
    }
    AMediaCodec_delete(decoder->codec);
    decoder->codec = NULL;
    decoder->shutdown_output = false;
    // 释放 frame_input_time 动态分配的内存
    if (frame_input_time) {
        free(frame_input_time);
        frame_input_time = NULL;
    }
    total_decode_latency = 0.0;
    frames_decoded_in_last_second = 0;
    frame_index=0;
}


void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
    if(decoder->codec)
        kill_decoder(decoder);
    chiaki_mutex_fini(&decoder->codec_mutex);
}

typedef struct {
    ChiakiSession *session;
    void *user;
} ThreadParams;

void android_chiaki_video_decoder_set_surface(ChiakiSession *session,AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
    chiaki_mutex_lock(&decoder->codec_mutex);

    if(!surface)
    {
        if(decoder->codec)
        {
            kill_decoder(decoder);
            CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
        }
        return;
    }

    if(decoder->codec)
    {
#if __ANDROID_API__ >= 23
        CHIAKI_LOGI(decoder->log, "Video decoder already initialized, swapping surface");
		ANativeWindow *new_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
		AMediaCodec_setOutputSurface(decoder->codec, new_window);
		ANativeWindow_release(decoder->window);
		decoder->window = new_window;
#else
        CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
        goto beach;
    }

    decoder->window = ANativeWindow_fromSurface(env, surface);

    const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
    CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

    decoder->codec = AMediaCodec_createDecoderByType(mime);
    if(!decoder->codec)
    {
        CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s", mime);
        goto error_surface;
    }

    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);

    media_status_t r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
    if(r != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
        AMediaFormat_delete(format);
        goto error_codec;
    }

    r = AMediaCodec_start(decoder->codec);
    AMediaFormat_delete(format);
    if(r != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
        goto error_codec;
    }

    ThreadParams *params = malloc(sizeof(ThreadParams));
    params->session = session;
    params->user = decoder;

    ChiakiErrorCode err = chiaki_thread_create(&decoder->output_thread, android_chiaki_video_decoder_output_thread_func, params);
    if(err != CHIAKI_ERR_SUCCESS)
    {
        CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
        goto error_codec;
    }

    goto beach;

    error_codec:
    AMediaCodec_delete(decoder->codec);
    decoder->codec = NULL;

    error_surface:
    ANativeWindow_release(decoder->window);
    decoder->window = NULL;

    beach:
    chiaki_mutex_unlock(&decoder->codec_mutex);
}


// 解码延迟计算（每秒一次平均解码延迟输出）
static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
    ThreadParams *params = (ThreadParams *)user;
    AndroidChiakiVideoDecoder *decoder = params->user;

    ChiakiSession *session = params->session;

    while (1)
    {
        AMediaCodecBufferInfo info;
        ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
        if (status >= 0)
        {
            // 获取当前时间戳（解码完成时的时间）
            struct timespec output_time;
            clock_gettime(CLOCK_MONOTONIC, &output_time);

            // 查找对应的输入时间戳
            struct timespec input_time = frame_input_time[info.presentationTimeUs % frame_index].input_time;

            // 计算解码延迟
//            double decode_latency = (output_time.tv_sec - input_time.tv_sec) +
//                                    (output_time.tv_nsec - input_time.tv_nsec) / 1e9; // 转换为秒
            long sec_diff = output_time.tv_sec - input_time.tv_sec;
            long nsec_diff = output_time.tv_nsec - input_time.tv_nsec;

            if (nsec_diff < 0) {
                nsec_diff += 1e9;  // 借 1 秒，纳秒部分加上 1e9
                sec_diff -= 1;     // 秒数减去 1
            }
            double decode_latency = sec_diff + nsec_diff / 1e9;


            // 累积解码延迟
            total_decode_latency += decode_latency;
            frames_decoded_in_last_second++;

            // 获取当前时间并计算FPS
            struct timespec current_time;
            clock_gettime(CLOCK_MONOTONIC, &current_time);

//            double time_diff = (current_time.tv_sec - last_fps_time.tv_sec) +
//                               (current_time.tv_nsec - last_fps_time.tv_nsec) / 1e9; // 转换为秒

            long sec_diff2 = current_time.tv_sec - last_fps_time.tv_sec;
            long nsec_diff2 = current_time.tv_nsec - last_fps_time.tv_nsec;

            if (nsec_diff2 < 0) {
                nsec_diff2 += 1e9;  // 借 1 秒，纳秒部分加上 1e9
                sec_diff2 -= 1;     // 秒数减去 1
            }

            double time_diff = sec_diff2 + nsec_diff2 / 1e9;

            // 每秒报告一次解码延迟和帧数
            if (time_diff >= 1.0)
            {
                // 计算过去一秒的平均解码延迟
                if (frames_decoded_in_last_second > 0)
                {
                    double avg_decode_latency = total_decode_latency / frames_decoded_in_last_second;
//                    CHIAKI_LOGD(decoder->log, "axixi=Average Decode Latency in last second: %.2f ms", avg_decode_latency * 1000);
//                    CHIAKI_LOGD(decoder->log, "axixi=Frames decoded in last second: %lu", frames_decoded_in_last_second);
                    char log_message[100];  // 假设最大长度为100字符
                    // 使用 snprintf 格式化字符串并存储到 log_message 中
                    snprintf(log_message, sizeof(log_message), "AxiDecoded|%.2f ms| %lu", avg_decode_latency * 1000, frames_decoded_in_last_second);
                    ChiakiEvent event = { 0 };
                    event.type = CHIAKI_EVENT_QUIT;
                    event.quit.reason_str= log_message;
                    session->event_cb(&event,session->event_cb_user);
                }

                // 重置统计
                total_decode_latency = 0.0;
                frames_decoded_in_last_second = 0;
                last_fps_time = current_time;
            }

            // 释放解码器输出缓冲区
            AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, info.size != 0);

            // 如果解码结束，则退出
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
            {
                CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
                break;
            }
        }
        else
        {
            chiaki_mutex_lock(&decoder->codec_mutex);
            bool shutdown = decoder->shutdown_output;
            chiaki_mutex_unlock(&decoder->codec_mutex);
            if (shutdown)
            {
                CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected shutdown after reported error");
                break;
            }
        }
    }

    CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");
    return NULL;
}

// 修改输入帧数据处理部分以记录时间戳
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, void *user)
{
    bool r = true;
    AndroidChiakiVideoDecoder *decoder = user;
    chiaki_mutex_lock(&decoder->codec_mutex);

    if (!decoder->codec)
    {
        CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not initialized!");
        goto beach;
    }

    while (buf_size > 0)
    {
        ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
        if (codec_buf_index < 0)
        {
            CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
            r = false;
            goto beach;
        }

        size_t codec_buf_size;
        uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
        size_t codec_sample_size = buf_size;
        if (codec_sample_size > codec_buf_size)
        {
            codec_sample_size = codec_buf_size;
        }
        memcpy(codec_buf, buf, codec_sample_size);

        // 记录帧输入时间戳
        struct timespec input_time;
        clock_gettime(CLOCK_MONOTONIC, &input_time);

        // 存储时间戳到数组中
        frame_input_time = realloc(frame_input_time, sizeof(FrameTimestamp) * (frame_index + 1)); // 动态扩展
        frame_input_time[frame_index].input_time = input_time;
        frame_index++;

        media_status_t r = AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size, decoder->timestamp_cur++, 0);
        if (r != AMEDIA_OK)
        {
            CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d", (int)r);
        }
        buf += codec_sample_size;
        buf_size -= codec_sample_size;
    }

    beach:
    chiaki_mutex_unlock(&decoder->codec_mutex);
    return r;
}