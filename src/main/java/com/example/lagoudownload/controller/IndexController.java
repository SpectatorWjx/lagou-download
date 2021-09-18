package com.example.lagoudownload.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @author wjx
 * @description TODO
 * @date 2021/9/17
 */
@Controller
public class IndexController {

    @RequestMapping(value = {"","/"})
    public String index(){
        return "index";
    }

}
