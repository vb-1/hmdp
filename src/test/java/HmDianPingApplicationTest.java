import com.hmdp.HmDianPingApplication;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest(classes = HmDianPingApplication.class)
public class HmDianPingApplicationTest {
    @Resource
    private ShopServiceImpl shopServiceImpl;

    @Test
    void testSaveShop(){
        shopServiceImpl.saveShoptoRedis(1L,10L);
    }
}
