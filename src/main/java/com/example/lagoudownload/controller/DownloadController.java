package com.example.lagoudownload.controller;

import com.example.lagoudownload.domain.DownloadType;
import com.example.lagoudownload.param.CourseDownloadParam;
import com.example.lagoudownload.support.BigCourseDownloader;
import com.example.lagoudownload.support.CmdExecutor;
import com.example.lagoudownload.support.Downloader;
import com.example.lagoudownload.support.ExecutorService;
import com.example.lagoudownload.utils.ZipUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * @author wjx
 * @description TODO
 * @date 2021/9/18
 */
@Controller
@Slf4j
@RequestMapping("download")
public class DownloadController {


    @Value("${mp4_dir}")
    private String savePath;
    @Value("${mp4_xunlianying_dir}")
    private String saveBigPath;

    @Value("${downloadType}")
    private String downloadType;

    @PostMapping("course")
    public Map<String, Object> downloadCourse(@RequestParam String courseId, String cookie , HttpServletResponse response) throws InterruptedException, IOException {
        Map<String, Object> map = new HashMap<>();
        File zipFile = new File(savePath + File.separator + courseId+".zip");
        if(!zipFile.exists()) {
            downloadToLocal(courseId, cookie);
        }
        // 配置文件下载
        response.setHeader("content-type", "application/octet-stream");
        response.setContentType("application/octet-stream");
        // 下载文件能正常显示中文
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(zipFile.getName(), "UTF-8"));

        // 实现文件下载
        byte[] buffer = new byte[1024];
        FileInputStream fis = null;
        BufferedInputStream bis = null;
        try {
            fis = new FileInputStream(zipFile);
            bis = new BufferedInputStream(fis);
            OutputStream os = response.getOutputStream();
            int i = bis.read(buffer);
            while (i != -1) {
                os.write(buffer, 0, i);
                i = bis.read(buffer);
            }
            log.info("Download the song successfully!");
        }catch (Exception e) {
            log.info("Download the song failed!");
        }
        finally {
            if (bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @PostMapping("course/local")
    @ResponseBody
    public Map<String, Object> downloadCourse(@RequestParam String courseId, @RequestParam String cookie) throws InterruptedException, IOException {
        Map<String, Object> map = new HashMap<>();
        File zipFile = new File(savePath + File.separator + courseId+".zip");
        if(!zipFile.exists()) {
            downloadToLocal(courseId, cookie);
        }
        map.put("code", 200);
        map.put("message","success");
        return map;
    }

    private void downloadToLocal(String courseId, String cookie) throws InterruptedException, FileNotFoundException {

        File zipFile = new File(savePath + File.separator + courseId+".zip");
        if(StringUtils.isEmpty(cookie)){
            throw new RuntimeException("课程不存在，请填写cookie重新下载");
        }
        //下载到服务器
        try {
            int status = CmdExecutor.executeCmd(new File("."), "ffmpeg", "-version");
            log.debug("检查ffmpeg是否存在,{}", status);
        } catch (Exception e) {
            log.error("{}", e.getMessage());
        }
        log.info("开始下载课程 专栏ID列表：{}", courseId);
        Downloader downloader = new Downloader(courseId, savePath,
                DownloadType.loadByCode(Integer.valueOf(downloadType)), cookie);
        try {
            downloader.start();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("\n====>程序运行完成");
        ExecutorService.tryTerminal();
        //打包
        String newSavePath = savePath + File.separator + getFile(savePath, courseId+"_");
        FileOutputStream fos1 = new FileOutputStream(zipFile);
        ZipUtils.toZip(newSavePath, fos1, true);

    }

//    @ResponseBody
//    @PostMapping("big/course")
//    public Map downloadBigCourse(@RequestParam String courseId, @RequestParam String cookie) throws InterruptedException, IOException {
//
//
//        BigCourseDownloader downloader = new BigCourseDownloader(courseId, saveBigPath, cookie);
//
//        Thread logThread = new Thread(() -> {
//            while (true) {
//                log.info("Thread pool:{}", ExecutorService.getExecutor());
//                try {
//                    Thread.sleep(1000);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        }, "log-thread");
//        logThread.setDaemon(true);
//        downloader.start();
//        ExecutorService.tryTerminal();
//        HashMap map = new HashMap();
//        map.put("code", 200);
//        map.put("message", "下载完成");
//        return map;
//    }



    public String getFile(String srcDir, String suffix) {
        File dir = new File(srcDir);
        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith(suffix);
            }
        };
        String[] children = dir.list(filter);
        return children[0];
    }


}
