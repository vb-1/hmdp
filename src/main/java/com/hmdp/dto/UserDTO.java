package com.hmdp.dto;

import com.hmdp.entity.User;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;

//    public UserDTO(User userEntity) {
//        if (userEntity != null) {
//            this.id = userEntity.getId();
//            this.nickName = userEntity.getNickName();
//            // ... 其他属性的转换
//        }
//    }
}
