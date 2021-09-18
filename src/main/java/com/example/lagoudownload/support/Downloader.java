package com.example.lagoudownload.support;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.example.lagoudownload.domain.CourseInfo;
import com.example.lagoudownload.domain.DownloadType;
import com.example.lagoudownload.domain.LessonInfo;
import com.example.lagoudownload.request.HttpAPI;
import com.example.lagoudownload.task.VideoInfoLoader;
import com.example.lagoudownload.utils.ReadTxt;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.example.lagoudownload.support.ExecutorService.COUNTER;

/**
 * 下载器
 *
 * @author suchu
 * @since 2019年8月2日
 */
@Slf4j
public class Downloader {

    /**
     * 拉钩视频课程地址
     */
    @Getter
    private String courseId;

    /**
     * 课程名字
     */
    private String courseName;
    /**
     * 视频保存路径
     */
    @Getter
    private String savePath;

    private File basePath;

    private File textPath;

    private CountDownLatch latch;

    private String cookie;

    /**
     * 需要使用线程安全的List
     */
    private volatile List<MediaLoader> mediaLoaders;

    private long start;

    private DownloadType downloadType = DownloadType.VIDEO;
    @Setter
    private Predicate<CourseInfo.Lesson> debugFilter = lesson -> true;

    public Downloader(String courseId, String savePath, String cookie) {
        this.courseId = courseId;
        this.savePath = savePath;
        this.cookie = cookie;
    }

    public Downloader(String courseId, String savePath, DownloadType downloadType, String cookie) {
        this.courseId = courseId;
        this.savePath = savePath;
        this.downloadType = downloadType;
        this.cookie = cookie;
    }

    public void start() throws InterruptedException {
        start = System.currentTimeMillis();
        List<LessonInfo> lessons = parseLessonInfo();
        if (!CollectionUtil.isEmpty(lessons)) {
            int i = parseVideoInfo(courseId, lessons, this.downloadType);
            if (i > 0) {
                log.info("开始下载 ：{}", courseName);
                downloadMedia(i);
            } else {
                //ConfigUtil.addCourse(courseId);
                log.info("===>《{}》所有课程都下载完成了", courseName);
            }
        }
    }

    /**
     * @return 解析课程列表信息
     */
    private List<LessonInfo> parseLessonInfo() {
        List<LessonInfo> lessonInfoList = new ArrayList<>();
        // TODO retry
        CourseInfo courseInfo = HttpAPI.getCourseInfo(this.courseId, this.cookie);
        if (!courseInfo.getHasBuy()) {
            log.warn("课程:{}没有购买，无法下载", courseInfo.getCourseName());
            return Collections.emptyList();
        }
        courseName = courseInfo.getCourseName();
        this.basePath = new File(savePath, this.courseId + "_" + courseName);
        if (!basePath.exists()) {
            basePath.mkdirs();
            log.info("视频存放文件夹{}", basePath.getAbsolutePath());
        }
        this.textPath = new File(this.basePath, "文档");
        if (!textPath.exists()) {
            textPath.mkdirs();
            log.info("文档存放文件夹{}", textPath.getAbsolutePath());
        }
        if (CollectionUtil.isEmpty(courseInfo.getCourseSectionList())) {
            log.error("《{}》课程为空", courseName);
            return Collections.emptyList();
        }

        StringBuilder sb = new StringBuilder();
        Map<String, AtomicInteger> map = new HashMap();

        log.info("====>正在下载《{}》 courseId={}", courseName, this.courseId);
        for (CourseInfo.Section section : courseInfo.getCourseSectionList()) {
            if (!CollectionUtil.isEmpty(section.getCourseLessons())) {
                List<LessonInfo> lessons = section
                        .getCourseLessons()
                        .stream()
                        .filter(debugFilter)
                        .filter(lesson -> {
                            StringJoiner sj = new StringJoiner("  ||  ");
                            sj.add(lesson.getId().toString());
                            String statusName = StringUtils.replace(lesson.getStatus(), "UNRELEASE", "没有发布");
                            statusName = StringUtils.replace(statusName, "RELEASE", "已发布");
                            sj.add(statusName);

                            AtomicInteger count = map.get(statusName);
                            count = Objects.isNull(count) ? new AtomicInteger(0) : count;
                            count.incrementAndGet();
                            map.put(statusName, count);

                            sj.add(lesson.getTheme());
                            sj.add(Optional.ofNullable(lesson.getVideoMediaDTO())
                                    .orElse(new CourseInfo.VideoMedia()).getEncryptedFileId());
                            sb.append(sj).append("\n");
                            if (!"RELEASE".equals(lesson.getStatus())) {
                                log.info("课程:【{}】 [未发布]", lesson.getTheme());
                                return false;
                            }
                            return true;
                        }).filter(lesson -> {
                                    if (DocHistory.contains(savePath,lesson.getId() + "", lesson.getTheme(), courseId, courseName)
                                            && Mp4History.contains(savePath, lesson.getId() + "", lesson.getTheme(), courseId, courseName)) {
                                        log.debug("课程视频和文章【{}】已经下载过了", lesson.getTheme());
                                        return false;
                                    }
                                    return true;
                                }
                        ).map(lesson -> {
                            String fileId = null;
                            String fileEdk = null;
                            String fileUrl = null;
                            if (null != lesson.getVideoMediaDTO()) {
                                fileId = lesson.getVideoMediaDTO().getFileId();
                                fileEdk = lesson.getVideoMediaDTO().getFileEdk();
                                fileUrl = lesson.getVideoMediaDTO().getFileUrl();
                            }
                            log.debug("解析到课程信息：【{}】,appId:{},fileId:{}", lesson.getTheme(), lesson.getAppId(), fileId);
                            return LessonInfo.builder().lessonId(lesson.getId() + "").lessonName(lesson.getTheme()).fileId(fileId).appId(lesson.getAppId()).fileEdk(fileEdk).fileUrl(fileUrl).build();
                        }).collect(Collectors.toList());
                lessonInfoList.addAll(lessons);
            } else {
                log.error("获取课程视频列表信息失败");
            }
        }

        //保存课程信息到目录
        try {
            File file = new File(basePath, "课程列表信息.txt");
            FileUtil.del(file);

            StringJoiner sj1 = new StringJoiner("   ");
            map.forEach((key, value) -> {
                sj1.add(key + ": " + value.get());
            });
            sb.insert(0, sj1 + "\n\n");
            IoUtil.writeUtf8(new FileOutputStream(file), true, sb);
        } catch (IOException e) {
            log.error("{}", e);
        }

        return lessonInfoList;
    }

    /**
     * 解析课程得到视频信息
     *
     * @param courseId
     * @param lessonInfoList
     * @param downloadType
     * @return
     */
    private int parseVideoInfo(String courseId, List<LessonInfo> lessonInfoList, DownloadType downloadType) {
        AtomicInteger videoSize = new AtomicInteger();
        latch = new CountDownLatch(lessonInfoList.size());
        // 这里使用的线程安全的容器，否则多线程添加应该会出现问题. Vector的啊add()方法加了锁synchronized
        mediaLoaders = new Vector<>();
        lessonInfoList.forEach(lessonInfo -> {
            String lessonId = lessonInfo.getLessonId();
            String lessonName = lessonInfo.getLessonName();
            if (Mp4History.contains(savePath,lessonId, lessonName, courseId, courseName) && DocHistory.contains(savePath, lessonId, lessonName, courseId, courseName)) {
                log.warn("课程【{}】已经下载过了", lessonName);
                latch.countDown();
                COUNTER.incrementAndGet();
            } else {
                videoSize.getAndIncrement();
                VideoInfoLoader loader = new VideoInfoLoader(courseId, courseName, lessonId, lessonName,
                        lessonInfo.getAppId(), lessonInfo.getFileId(),
                        lessonInfo.getFileUrl(), downloadType, cookie);
                loader.setMediaLoaders(mediaLoaders);
                loader.setBasePath(this.basePath);
                loader.setTextPath(this.textPath);
                loader.setLatch(latch);
                ExecutorService.execute(loader);
            }
        });
        return videoSize.intValue();
    }

    /**
     * 下载课程解析后的视频
     *
     * @param i 理论需要下载的视频数量
     * @throws InterruptedException interruptedException
     */
    private void downloadMedia(int i) throws InterruptedException {
        log.debug("等待《{}》获取视频信息任务完成...", courseName);
        latch.await();
        int mediaLoadersSize = mediaLoaders.size();
        if (mediaLoadersSize != i) {
            String message = String.format("《%s》视频META信息没有全部下载成功: success:%s,total:%s", courseName, mediaLoaders.size(), i);
            log.error("{}", message);
            File file = new File(basePath, "下载失败.txt");
            ReadTxt readTxt = new ReadTxt();
            readTxt.writeFile(file.getAbsolutePath(), message);
            if (mediaLoadersSize <= 0) {
                return;
            }
        } else {
            log.info("《{}》所有视频META信息获取成功 total：{}", courseName, mediaLoadersSize);
        }

        // 执行下载视频的工作单元
        CountDownLatch all = new CountDownLatch(mediaLoadersSize);
        for (MediaLoader loader : mediaLoaders) {
            loader.setLatch(all);
            ExecutorService.getExecutor().execute(loader);
        }
        all.await();

        long end = System.currentTimeMillis();
        log.info("《{}》所有视频处理耗时:{} s", courseName, (end - start) / 1000);
        log.info("《{}》视频输出目录:{}\n\n", courseName, this.basePath.getAbsolutePath());
        if (!Stats.isEmpty()) {
            log.info("\n\n失败统计信息\n\n");
            Stats.failedCount.forEach((key, value) -> System.out.println(key + " -> " + value.get()));
        }
    }
}
