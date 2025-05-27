import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.HmDianPingApplication;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest(classes = HmDianPingApplication.class)
public class HmDianPingApplicationTest {
    @Resource
    private ShopServiceImpl shopServiceImpl;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Test
    void testSaveShop(){
        Shop shop = shopServiceImpl.getById(1L);

        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }
    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time:" + (end - begin));
    }

    @Resource
    IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void testGetAll() {

        List<User> users = userService.list();
//        for(User user : users){

//            System.out.println(user);
//        }
//        System.exit(0);
        users.forEach(
                user -> {

                    //          7.1,随机生成token,作为登录令牌
                    String token = UUID.randomUUID().toString(true);
//        7.2,将User对象转化为HashMap存储
                    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                    File file = new File("C:\\document\\javacode\\reset_dianping\\token.txt");
                    FileOutputStream output = null;
                    try {

                        output = new FileOutputStream(file, true);
                        byte[] bytes = token.getBytes();
                        output.write(bytes);
                        output.write("\r\n".getBytes());
                    } catch (Exception e) {

                        throw new RuntimeException(e);
                    } finally {

                        try {

                            output.close();
                        } catch (IOException e) {

                            throw new RuntimeException(e);
                        }
                    }
                    Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
//        7.3,存储
                    String tokenKey = LOGIN_USER_KEY + token;
                    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        7.4,设置token有效期
                    stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
                }
        );
    }
}
