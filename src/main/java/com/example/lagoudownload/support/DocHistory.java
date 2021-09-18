package com.example.lagoudownload.support;

import cn.hutool.core.io.FileUtil;
import com.example.lagoudownload.utils.FileUtils;
import com.example.lagoudownload.utils.ReadTxt;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * mp4视频下载历史信息记录到文件中
 *
 * @author eric
 */
public class DocHistory {

    private static volatile Set<String> historySet = new HashSet<>();

    /**
     * 记录已经下载过的视频id，不要重复下载了。
     */
    static String filePath = "doc.txt";

    static {
        loadHistory();
    }

    /**
     * 下载完成之后追加到历史文件
     *
     * @param lessonId
     */
    public static void append(String lessonId) {
        historySet.add(lessonId);
        new ReadTxt().writeFile(filePath, lessonId);
    }

    public static Set<String> loadHistory() {
        Set<String> set = new ReadTxt().readFile(filePath);
        historySet.addAll(set);
        return historySet;
    }

    public static boolean contains(String savePath, String lessonId, String lessonName, String courseId, String courseName) {
        lessonName = FileUtils.getCorrectFileName(lessonName);

        String path = String.join(File.separator,
                savePath,
                courseId + "_" + courseName + File.separator + "文档",
                "[" + lessonId + "] " + lessonName + ".md");
        boolean exist = FileUtil.exist(path);

        //return historySet.contains(lessonId) && exist;
        return exist;
    }

}
