import org.springframework.beans.FatalBeanException;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisBusyException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.SocketTimeoutException;

public class JedisRedisClient implements IRedisClient {

    private RedisInstance redisInstance;
    private Object lock = new Object();
    private JedisPool pool;
    private JedisPoolConfig config;
    private int connectTimeout = 5000;
    private int operationTimeout = 5000;
    private int port = 6380;
    private int maxConnections = 400;

    public JedisRedisClient(RedisInstance instance)
    {
        redisInstance = instance;
    }

    public String getHostName() {return redisInstance.getHostname(); }

    @Override
    public String get(String key) {
        try(Jedis jedis = getPoolInstance().getResource())
        {
            return jedis.get(key);
        } catch (Exception ex) {
            LogError(ex);
        }
        return null;
    }

    @Override
    public void ping() {
        try(Jedis jedis = getPoolInstance().getResource())
        {
            jedis.ping();
        } catch (Exception ex) {
            LogError(ex);
        }
    }

    public String info()
    {
        try(Jedis jedis = getPoolInstance().getResource())
        {
            return jedis.info();
        } catch (Exception ex) {
            LogError(ex);
        }

        return "";
    }

    @Override
    public void set(String key, String value) {
        try(Jedis jedis = getPoolInstance().getResource())
        {
            jedis.set(key, value);
        } catch (Exception ex) {
            LogError(ex);
        }
    }

    public JedisPool getPoolInstance() {
        if (pool == null) { // avoid synchronization lock if initialization has already happened
            synchronized(lock) {
                if (pool == null) { // don't re-initialize if another thread beat us to it.
                    JedisPoolConfig poolConfig = getPoolConfig();
                    boolean useSsl = port == 6380 ? true : false;
                    int db = 0;
                    String clientName =  Program.AppName + ":Jedis";
                    SSLSocketFactory sslSocketFactory = null; // null means use default
                    SSLParameters sslParameters = null; // null means use default
                    HostnameVerifier hostnameVerifier = new SimpleHostNameVerifier(redisInstance.getHostname());
                    pool = new JedisPool(poolConfig, redisInstance.getHostname(), port, connectTimeout, operationTimeout, redisInstance.getPassword(), db,
                            clientName, useSsl, sslSocketFactory, sslParameters, hostnameVerifier);
                }
            }
        }
        return pool;
    }

    private JedisPoolConfig getPoolConfig() {
        if (config == null) {
            JedisPoolConfig poolConfig = new JedisPoolConfig();

            // Each thread trying to access Redis needs its own Jedis instance from the pool.
            // Using too small a value here can lead to performance problems, too big and you have wasted resources.

            poolConfig.setMaxTotal(maxConnections);
            poolConfig.setMaxIdle(maxConnections);

            // Using "false" here will make it easier to debug when your maxTotal/minIdle/etc settings need adjusting.
            // Setting it to "true" will result better behavior when unexpected load hits in production
            poolConfig.setBlockWhenExhausted(true);

            // How long to wait before throwing when pool is exhausted
            poolConfig.setMaxWaitMillis(5000);

            // This controls the number of connections that should be maintained for bursts of load.
            // Increase this value when you see pool.getResource() taking a long time to complete under burst scenarios
            poolConfig.setMinIdle(50);

            config = poolConfig;
        }

        return config;
    }

    public static void LogError(Exception ex)
    {
        if (ex instanceof JedisConnectionException) {
            Throwable cause = ex.getCause();
            if (cause != null && cause instanceof SocketTimeoutException)
                Logging.write("T");
            else
                Logging.write("C");
        } else if (ex instanceof JedisBusyException) {
            Logging.write("B");
        } else if (ex instanceof JedisException) {
            Logging.write("E");
        } else if (ex instanceof IOException){
            Logging.write("C");
        } else {
            Logging.logException(ex);
            throw new FatalBeanException(ex.getMessage(), ex); // unexpected exception type, so abort test for investigation
        }
    }
    private static class SimpleHostNameVerifier implements HostnameVerifier {

        private String exactCN;
        private String wildCardCN;
        public SimpleHostNameVerifier(String cacheHostname)
        {
            exactCN = "CN=" + cacheHostname;
            wildCardCN = "CN=*" + cacheHostname.substring(cacheHostname.indexOf('.'));
        }

        public boolean verify(String s, SSLSession sslSession) {
            try {
                String cn = sslSession.getPeerPrincipal().getName();
                return cn.equalsIgnoreCase(wildCardCN) || cn.equalsIgnoreCase(exactCN);
            } catch (SSLPeerUnverifiedException ex) {
                return false;
            }
        }
    }
}
