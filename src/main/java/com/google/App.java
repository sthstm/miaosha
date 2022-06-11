package com.google;

import com.google.dao.UserDOMapper;
import com.google.dataobject.UserDO;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(scanBasePackages= {"com.google"})
@RestController
@MapperScan("com.google.dao")
public class App 
{

    @Autowired
    private UserDOMapper userDOMapper;


    @RequestMapping("/")
    public String home() {

        UserDO userDO = userDOMapper.selectByPrimaryKey(1);
        if (userDO == null) {
            return "用户对象不存在";
        }
        return userDO.getName();
    }

    public static void main( String[] args )
    {
        SpringApplication.run(App.class, args);
    }
}
