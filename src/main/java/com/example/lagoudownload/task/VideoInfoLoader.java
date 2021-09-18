package com.example.lagoudownload.task;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.example.lagoudownload.domain.CourseCommentListInfo;
import com.example.lagoudownload.domain.CourseLessonDetail;
import com.example.lagoudownload.domain.DownloadType;
import com.example.lagoudownload.domain.PlayHistory;
import com.example.lagoudownload.request.HttpAPI;
import com.example.lagoudownload.support.*;
import com.example.lagoudownload.task.aliyunvod.AliyunVoDEncryptionMediaLoader;
import com.example.lagoudownload.utils.FileUtils;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static com.example.lagoudownload.support.ExecutorService.COUNTER;

/**
 * 视频metaInfo 加载器
 *
 * @author suchu
 * @since 2019年8月3日
 */
@Slf4j
public class VideoInfoLoader extends AbstractRetryTask implements NamedTask {
    private final static int maxRetryCount = 3;
    private  String videoName;
    private  String newVideoName;
    private  String courseId;
    private  String courseName;
    private  String appId;
    private  String fileId;
    private  String fileUrl;
    private  String lessonId;
    private String cookie;
    private int retryCount = 0;
    @Setter
    private File basePath;
    @Setter
    private File textPath;

    //是否强制下载mp4文件
    @Setter
    private boolean forceDownloadMp4 = true;
    /**
     * 这个一定要是一个线程安全的容器，否则会有并发问题
     */
    @Setter
    private List<MediaLoader> mediaLoaders;

    //解析所有课程meta信息的Latch
    @Setter
    private CountDownLatch latch;

    private final String UNRELEASE = "UNRELEASE";


    /**
     * 默认只下载视频
     */
    private DownloadType downloadType = DownloadType.VIDEO;

    public VideoInfoLoader(String courseId, String courseName, String lessonId, String lessonName,
                           String appId, String fileId, String fileUrl, DownloadType downloadType, String cookie) {
        this.courseId = courseId;
        this.courseName = courseName;
        this.lessonId = lessonId;
        this.videoName = lessonName;
        this.newVideoName = "[" + lessonId + "] " + lessonName;
        this.appId = appId;
        this.fileId = fileId;
        this.fileUrl = fileUrl;
        this.downloadType = downloadType;
        this.cookie = cookie;
    }

    @Override
    public boolean canRetry() {
        return retryCount < maxRetryCount;
    }

    @Override
    protected void retry(Throwable throwable) {
        super.retry(throwable);
        log.warn("获取视频:【{}】信息失败:", videoName, throwable);
        retryCount += 1;
        log.info("第:{}次重试获取:{}", retryCount, videoName);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e1) {
            log.error("", e1);
        }
        ExecutorService.execute(this);
    }

    @Override
    public void retryComplete() {
        log.error(" video:【{}】最大重试结束:{}", videoName, maxRetryCount);
        COUNTER.incrementAndGet();
        latch.countDown();
    }

    @Override
    public void action() {
        CourseLessonDetail courseDetail = HttpAPI.getCourseLessonDetail(lessonId, videoName, cookie);
        List<CourseCommentListInfo.CourseCommentList> courseCommentList = HttpAPI.getCourseCommentList(courseId, lessonId, cookie);
        //下载视频
        if (DownloadType.needVideo(this.downloadType) && !Mp4History.contains(lessonId, videoName, courseId, courseName, cookie)) {
            downMp4(courseDetail);
        } else {
            mediaLoaders.add(new MediaLoader.EmptyMediaLoader());
        }
        // 下载文档
        if (DownloadType.needText(this.downloadType) && !DocHistory.contains(lessonId, videoName, courseId, courseName, cookie)) {
            String textContent = courseDetail.getTextContent();
            if (textContent != null) {

                String commentContent = courseCommentList.stream().map(courseComment -> {
                    String text = String.format("##### %s：\n> %s\n",
                            courseComment.getNickName(), courseComment.getComment());
                    CourseCommentListInfo.CourseCommentList replayComment = courseComment.getReplayComment();
                    if (Objects.nonNull(replayComment)) {
                        text = text + String.format("\n ###### &nbsp;&nbsp;&nbsp; %s：\n> &nbsp;&nbsp;&nbsp; %s\n", replayComment.getNickName(), replayComment.getComment());
                    }
                    return text;
                })
                        .collect(Collectors.joining("\n"));
                commentContent = "\n\n---\n\n### 精选评论\n\n" + commentContent + "\n";

                //追加精选留言类型
                textContent += commentContent;

                String textFileName = "[" + lessonId + "] " + FileUtils.getCorrectFileName(videoName) + ".!md";
                FileUtils.writeFile(textPath, textFileName, textContent);
                FileUtils.replaceFileName(new File(textPath.getPath() + File.separator + textFileName), ".!md", ".md");
                DocHistory.append(lessonId);
            }
        }
        // 不可以移动到finally中调用
        latch.countDown();
    }

    public void downMp4(CourseLessonDetail courseDetail) {
        String status = courseDetail.getStatus();
        if (UNRELEASE.equals(status)) {
            log.info("视频:【{}】待更新", newVideoName);
            COUNTER.incrementAndGet();
            return;
        }
        CourseLessonDetail.VideoMedia videoMedia = courseDetail.getVideoMedia();
        if (videoMedia == null) {
            log.warn("视频信息获取失败{}", newVideoName);
            return;
        }

        String m3u8Url = videoMedia.getFileUrl();
        if (m3u8Url != null) {
            log.info("获取视频:【{}】m3u8播放地址成功:{}", newVideoName, m3u8Url);
        }
        if (!forceDownloadMp4) {
            dispatch();
        } else {
            MP4Downloader mp4Downloader = MP4Downloader.builder().appId(appId).basePath(basePath.getAbsoluteFile()).videoName(newVideoName).fileId(fileId).lessonId(lessonId).cookie(cookie).build();
            mediaLoaders.add(mp4Downloader);
        }
    }

    /**
     * 分配普通的下载器
     */
    private void dispatch() {
        PlayHistory playHistory = HttpAPI.getPlayHistory(lessonId, cookie);
        //阿里云私有加密
        if (playHistory.getEncryptMedia()) {
            AliyunVoDEncryptionMediaLoader m3u8 = new AliyunVoDEncryptionMediaLoader(playHistory.getAliPlayAuth(), newVideoName, basePath.getAbsolutePath(), playHistory.getFileId());
            mediaLoaders.add(m3u8);
        } else {
            MP4Downloader mp4Downloader = MP4Downloader.builder().appId(appId).basePath(basePath.getAbsoluteFile()).videoName(newVideoName).fileId(fileId).lessonId(lessonId).cookie(cookie).build();
            mediaLoaders.add(mp4Downloader);
        }
    }

    @Override
    public String getTaskDescription() {
        return videoName;
    }
}
