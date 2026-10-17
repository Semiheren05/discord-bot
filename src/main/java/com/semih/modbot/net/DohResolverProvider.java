package com.semih.modbot.net;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/**
 * Java'ya "adres sorgularını DohResolver ile yap" der.
 * src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider dosyası
 * sayesinde Java açılışta bunu otomatik bulur (Java 18 ve üzeri).
 */
public final class DohResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        return new DohResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return "ModBot şifreli DNS";
    }
}
