package com.example.demo.service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; // 需要手动导入或IDE自动导入
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepo;
//    初始化加密器
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Optional<User> findByPhone(String phone) {
        // Service 层调用 Repository 层的 findByPhone 函数
        return userRepo.findByPhone(phone);
    }

    // ---------------------- 注册 ----------------------
    public String register(User user) {

        // 用户名唯一
        if (userRepo.findByUsername(user.getUsername()).isPresent()) {
            return "用户名已被注册";
        }
        // 手机号唯一
        if (userRepo.findByPhone(user.getPhone()).isPresent()) {
            return "手机号已被注册";
        }

        // 密码强度检查
        if (!isValidPassword(user.getPassword())) {
            return "密码必须包含英文、数字、特殊字符，长度至少6位";
        }

        // 👉 TODO：这里以后要换成 BCrypt 加密
        //user.setPassword(user.getPassword());

        // 改了：加密密码
        user.setPassword(passwordEncoder.encode(user.getPassword()));


        userRepo.save(user);
        return "注册成功";
    }

    // ---------------------- 登录（新版，需要返回 User 对象） ----------------------
    public User loginAndReturnUser(String username, String password) {

        Optional<User> userOpt = userRepo.findByUsername(username);
        if (userOpt.isEmpty()) return null;

        User user = userOpt.get();
        /*
        👉 TODO：后续替换为 BCrypt 对比
        if (!user.getPassword().equals(password)) {
            return null;
        }
         */

        // ✅ 使用 matches 方法比对明文密码和数据库的密文
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return null;
        }



        return user;
    }

    // ---------------------- 找回密码 ----------------------
    public String resetPassword(String phone, String newPassword) {

        Optional<User> user = userRepo.findByPhone(phone);

        if (user.isEmpty()) return "手机号未注册";

        if (!isValidPassword(newPassword)) {
            return "密码必须包含英文、数字、特殊字符，长度至少6位";
        }

        User u = user.get();
        //u.setPassword(newPassword);  // TODO: 这里未来改为加密
        // ✅ 加密新密码
        u.setPassword(passwordEncoder.encode(newPassword));
        userRepo.save(u);

        return "密码重置成功";
    }

    // ---------------------- 密码校验 ----------------------
    private boolean isValidPassword(String pwd) {
        if (pwd == null || pwd.length() < 6) return false;

        boolean hasNum = Pattern.compile(".*\\d.*").matcher(pwd).matches();
        boolean hasLetter = Pattern.compile(".*[A-Za-z].*").matcher(pwd).matches();
        boolean hasSpecial = Pattern.compile(".*[^A-Za-z0-9].*").matcher(pwd).matches();

        return hasNum && hasLetter && hasSpecial;
    }

    // ---------------------- 用户资料获取 ----------------------
    public User getUser(Long id) {
        return userRepo.findById(id).orElse(null);
    }


    // ---------------------- 修改资料 ----------------------
    public void updateUser(User update) {
        User user = userRepo.findById(update.getId()).orElse(null);
        if (user == null) return;

        // ⚠ 这里不要让用户修改 username！
        // username 唯一，会破坏登录系统
        // 如果你未来需要修改 username，我们再单独写接口

        user.setGender(update.getGender());
        user.setAge(update.getAge());
        user.setPhone(update.getPhone());
        userRepo.save(user);
    }

    // ---------------------- 更新头像路径 ----------------------
    public void updateAvatar(Long userId, String path) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return;
        user.setAvatarPath(path);
        userRepo.save(user);
    }
}
