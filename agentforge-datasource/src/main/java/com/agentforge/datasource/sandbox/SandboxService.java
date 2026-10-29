package com.agentforge.datasource.sandbox;

import com.agentforge.datasource.dto.SandboxProviderInfo;
import com.agentforge.datasource.spi.SandboxProvider;
import com.agentforge.datasource.spi.SandboxRequest;
import com.agentforge.datasource.spi.SandboxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱门面：优先 Docker（可用时），否则回退本地 Process 沙箱。
 */
@Slf4j
@Service
public class SandboxService {

    private final List<SandboxProvider> providers;

    public SandboxService(List<SandboxProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    public SandboxResult execute(SandboxRequest request) {
        SandboxProvider docker = findByProviderName("docker");
        if (docker instanceof AvailabilityAware aware && aware.available()) {
            return docker.execute(request);
        }
        SandboxProvider process = findByProviderName("process");
        if (process == null) {
            return new SandboxResult(false, "", "当前环境无可用沙箱", -1, 0L, false);
        }
        return process.execute(request);
    }

    public List<SandboxProviderInfo> providers() {
        List<SandboxProviderInfo> infos = new ArrayList<>();
        for (SandboxProvider provider : providers) {
            if (provider instanceof AvailabilityAware aware) {
                infos.add(new SandboxProviderInfo(provider.name(), aware.available(),
                        aware.supportedLanguages(), aware.description()));
            } else {
                infos.add(new SandboxProviderInfo(provider.name(), true, List.of(), ""));
            }
        }
        return infos;
    }

    private SandboxProvider findByProviderName(String name) {
        for (SandboxProvider provider : providers) {
            if (name.equals(provider.name())) {
                return provider;
            }
        }
        return null;
    }
}
