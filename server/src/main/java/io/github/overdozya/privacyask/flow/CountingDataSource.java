package io.github.overdozya.privacyask.flow;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 턴당 DB 문장 수(06 6.3·6.4)를 세는 얇은 감싸개. 턴 측정(TurnMeter)이 걸린 스레드가 연결을 받을 때만 연결을 감싸고,
 * 그 연결에서 문장을 준비할 때마다 1을 센다. 측정이 없으면 원래 연결을 그대로 준다(기동·Flyway·테스트 부담 없음).
 * 원래 풀(Hikari)은 그대로이고, 닫기는 원래 데이터 소스로 넘긴다.
 */
public class CountingDataSource extends DelegatingDataSource implements Closeable {
    private static final Set<String> STATEMENT_METHODS = Set.of("prepareStatement", "createStatement", "prepareCall");

    public CountingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection c) {
        TurnMeter meter = TurnMeter.current();
        if (meter == null) {
            return c;
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, m, args) -> {
                    if (STATEMENT_METHODS.contains(m.getName())) {
                        meter.countStatement();
                    }
                    try {
                        return m.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public void close() throws java.io.IOException {
        if (getTargetDataSource() instanceof AutoCloseable a) {
            try {
                a.close();
            } catch (java.io.IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
        }
    }
}
