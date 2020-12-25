package cn.sp;

import cn.sp.constants.AdminConstants;
import cn.sp.exception.ShipException;
import cn.sp.pojo.dto.RegisterAppDTO;
import cn.sp.pojo.dto.UnregisterAppDTO;
import cn.sp.utils.IpUtil;
import cn.sp.utils.OkhttpTool;
import com.alibaba.nacos.api.annotation.NacosInjected;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by 2YSP on 2020/12/21
 */
public class AutoRegisterListener implements ApplicationListener<ContextRefreshedEvent> {

    private final static Logger LOGGER = LoggerFactory.getLogger(AutoRegisterListener.class);

    private volatile AtomicBoolean registered = new AtomicBoolean(false);

    private final ClientConfigProperties properties;

    @NacosInjected
    private NamingService namingService;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private final ExecutorService pool;

    /**
     * url list to ignore
     */
    private static List<String> ignoreUrlList = new LinkedList<>();

    static {
        ignoreUrlList.add("/error");
    }

    public AutoRegisterListener(ClientConfigProperties properties) {
        if (!check(properties)) {
            LOGGER.error("client config port,contextPath,appName adminUrl and version can't be empty!");
            throw new ShipException("client config port,contextPath,appName adminUrl and version can't be empty!");
        }
        this.properties = properties;
        pool = new ThreadPoolExecutor(1, 4, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    /**
     * check the ClientConfigProperties
     *
     * @param properties
     * @return
     */
    private boolean check(ClientConfigProperties properties) {
        if (properties.getPort() == null || properties.getContextPath() == null
                || properties.getVersion() == null || properties.getAppName() == null
                || properties.getAdminUrl() == null) {
            return false;
        }
        return true;
    }


    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        doRegister();
        registerShutDownHook();
    }

    /**
     * send unregister request to admin when jvm shutdown
     */
    private void registerShutDownHook() {
        final String url = "http://" + properties.getAdminUrl() + AdminConstants.UNREGISTER_PATH;
        final UnregisterAppDTO unregisterAppDTO = new UnregisterAppDTO();
        unregisterAppDTO.setAppName(properties.getAppName());
        unregisterAppDTO.setVersion(properties.getVersion());
        unregisterAppDTO.setIp(IpUtil.getLocalIpAddress());
        unregisterAppDTO.setPort(properties.getPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            OkhttpTool.post(url, unregisterAppDTO);
            LOGGER.info("[{}:{}] unregister from ship-admin success!", unregisterAppDTO.getAppName(), unregisterAppDTO.getVersion());
        }));
    }

    /**
     * register all interface info to register center
     */
    private void doRegister() {
        Instance instance = new Instance();
        instance.setIp(IpUtil.getLocalIpAddress());
        instance.setPort(properties.getPort());
        instance.setEphemeral(true);
        Map<String, String> metadataMap = new HashMap<>();
        metadataMap.put("version", properties.getVersion());
        metadataMap.put("appName", properties.getAppName());
        instance.setMetadata(metadataMap);
        List<String> serviceNames = getAllServiceName();
        for (String serviceName : serviceNames) {
            pool.execute(() -> {
                try {
                    // serviceName = url:version
                    namingService.registerInstance(serviceName, instance);
                } catch (NacosException e) {
                    LOGGER.error("register to nacos fail", e);
                    throw new ShipException("register to nacos fail");
                }
            });
        }
        // todo check register result
        LOGGER.info("register interface info to nacos success!");
        // send register request to ship-admin
        String url = "http://" + properties.getAdminUrl() + AdminConstants.REGISTER_PATH;
        RegisterAppDTO registerAppDTO = buildRegisterAppDTO(instance);
        OkhttpTool.post(url, registerAppDTO);
        LOGGER.info("register to ship-admin success!");
    }


    private RegisterAppDTO buildRegisterAppDTO(Instance instance) {
        RegisterAppDTO registerAppDTO = new RegisterAppDTO();
        registerAppDTO.setAppName(properties.getAppName());
        registerAppDTO.setContextPath(properties.getContextPath());
        registerAppDTO.setIp(instance.getIp());
        registerAppDTO.setPort(instance.getPort());
        registerAppDTO.setVersion(properties.getVersion());
        return registerAppDTO;
    }

    /**
     * get all http interface service list
     *
     * @return
     */
    private List<String> getAllServiceName() {
        List<String> serviceNames = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod method = entry.getValue();
            PatternsRequestCondition patternsCondition = mappingInfo.getPatternsCondition();
            serviceNames.addAll(patternsCondition.getPatterns());
        }
        List<String> result = new ArrayList<>();
        // replace / to .
        for (String serviceName : serviceNames) {
            if (ignoreUrlList.contains(serviceName)) {
                continue;
            }
            String url = properties.getContextPath() + serviceName;
            result.add(url.replace("/", ".") + ":" + properties.getVersion());
        }
        return result;
    }

}
