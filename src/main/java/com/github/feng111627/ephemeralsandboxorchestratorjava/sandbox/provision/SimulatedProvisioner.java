package com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.provision;

import com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox.domain.Sandbox;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地可复现的模拟资源配置器。
 *
 * spec 约定（分号分隔的 key=value）：
 * - image=...       任意镜像名（默认 demo:latest）
 * - fail=true       确定性地配置失败（错误原因确定、可复现）
 * - delayMillis=N   配置耗时 N 毫秒，用于模拟申请中断、并发交错
 *
 * 例：image=nginx;delayMillis=200
 *     image=demo;fail=true
 */
@Component
public class SimulatedProvisioner implements SandboxProvisioner {

    private static final Pattern FAIL_PATTERN = Pattern.compile("(?:^|;)\\s*fail\\s*=\\s*true\\s*(?:;|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELAY_PATTERN = Pattern.compile("(?:^|;)\\s*delayMillis\\s*=\\s*(\\d+)\\s*(?:;|$)");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("(?:^|;)\\s*image\\s*=\\s*([^;\\s]+)");

    @Override
    public ProvisionResult provision(Sandbox sandbox) {
        String spec = sandbox.getSpec() == null ? "" : sandbox.getSpec();

        Matcher delayMatcher = DELAY_PATTERN.matcher(spec);
        if (delayMatcher.find()) {
            long delay = Long.parseLong(delayMatcher.group(1));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ProvisionResult.fail("provisioning interrupted for sandbox " + sandbox.getId());
            }
        }

        if (FAIL_PATTERN.matcher(spec).find()) {
            return ProvisionResult.fail("injected provisioning failure (spec=" + spec + ")");
        }

        String image = "demo:latest";
        Matcher imageMatcher = IMAGE_PATTERN.matcher(spec);
        if (imageMatcher.find()) {
            image = imageMatcher.group(1);
        }
        String endpoint = sandbox.getId() + "." + image.replace(':', '.') + ".sandbox.local:8080";
        return ProvisionResult.ok(endpoint);
    }
}
