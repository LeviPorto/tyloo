package io.tyloo.repository;

import io.tyloo.Transaction;
import io.tyloo.repository.helper.ExpandTransactionSerializer;
import io.tyloo.repository.helper.JedisCallback;
import io.tyloo.repository.helper.RedisHelper;
import io.tyloo.serializer.KryoPoolSerializer;
import io.tyloo.serializer.ObjectSerializer;
import org.apache.log4j.Logger;
import redis.clients.jedis.*;

import javax.transaction.xa.Xid;
import java.util.*;

/*
 *
 * Redis缓存事务库
 * Jedis是Redis官方推荐的Java连接开发工具
 *
 * @Author:Zh1Cheung zh1cheunglq@gmail.com
 * @Date: 19:27 2019/5/17
 *
 */
public class RedisTransactionRepository extends CachableTransactionRepository {

    private static final Logger logger = Logger.getLogger(RedisTransactionRepository.class.getSimpleName());

    private JedisPool jedisPool;

    private String keyPrefix = "TCC:";

    private int fetchKeySize = 1000;

    private boolean isSupportScan = true;

    private boolean isForbiddenKeys = false;
    private ObjectSerializer serializer = new KryoPoolSerializer();

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public void setSerializer(ObjectSerializer serializer) {
        this.serializer = serializer;
    }

    public int getFetchKeySize() {
        return fetchKeySize;
    }

    public void setFetchKeySize(int fetchKeySize) {
        this.fetchKeySize = fetchKeySize;
    }

    public JedisPool getJedisPool() {
        return jedisPool;
    }

    public void setJedisPool(JedisPool jedisPool) {

        this.jedisPool = jedisPool;

        isSupportScan = RedisHelper.isSupportScanCommand(jedisPool.getResource());

        if (!isSupportScan && isForbiddenKeys) {
            throw new RuntimeException("Redis not support 'scan' command, " +
                    "and 'keys' command is forbidden, " +
                    "try update redis version higher than 2.8.0 " +
                    "or set 'isForbiddenKeys' to false");
        }
    }

    public void setSupportScan(boolean isSupportScan) {
        this.isSupportScan = isSupportScan;
    }

    public void setForbiddenKeys(boolean forbiddenKeys) {
        isForbiddenKeys = forbiddenKeys;
    }

    @Override
    protected int doCreate(final Transaction transaction) {

        try {
            Long statusCode = RedisHelper.execute(jedisPool, jedis -> {

                List<byte[]> params = new ArrayList<>();

                for (Map.Entry<byte[], byte[]> entry : ExpandTransactionSerializer.serialize(serializer, transaction).entrySet()) {
                    params.add(entry.getKey());
                    params.add(entry.getValue());
                }

                Object result = jedis.eval("if redis.call('exists', KEYS[1]) == 0 then redis.call('hmset', KEYS[1], unpack(ARGV)); return 1; end; return 0;".getBytes(),
                        Arrays.asList(RedisHelper.getRedisKey(keyPrefix, transaction.getXid())), params);

                return (Long) result;
            });
            return statusCode.intValue();

        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected int doUpdate(final Transaction transaction) {

        try {
            Long statusCode = RedisHelper.execute(jedisPool, jedis -> {

                transaction.updateTime();
                transaction.updateVersion();

                List<byte[]> params = new ArrayList<>();

                for (Map.Entry<byte[], byte[]> entry : ExpandTransactionSerializer.serialize(serializer, transaction).entrySet()) {
                    params.add(entry.getKey());
                    params.add(entry.getValue());
                }

                Object result = jedis.eval(String.format("if redis.call('hget',KEYS[1],'VERSION') == '%s' then redis.call('hmset', KEYS[1], unpack(ARGV)); return 1; end; return 0;",
                        transaction.getVersion() - 1).getBytes(),
                        Arrays.asList(RedisHelper.getRedisKey(keyPrefix, transaction.getXid())), params);

                return (Long) result;
            });

            return statusCode.intValue();
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected int doDelete(final Transaction transaction) {
        try {

            Long result = RedisHelper.execute(jedisPool, jedis -> jedis.del(RedisHelper.getRedisKey(keyPrefix, transaction.getXid())));

            return result.intValue();
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected Transaction doFindOne(final Xid xid) {

        try {
            Long startTime = System.currentTimeMillis();
            Map<byte[], byte[]> content = RedisHelper.execute(jedisPool, jedis -> jedis.hgetAll(RedisHelper.getRedisKey(keyPrefix, xid)));
            logger.info("redis find cost time :" + (System.currentTimeMillis() - startTime));

            if (content != null && content.size() > 0) {
                return ExpandTransactionSerializer.deserialize(serializer, content);
            }
            return null;
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected List<Transaction> doFindAllUnmodifiedSince(Date date) {

        List<Transaction> allTransactions = doFindAll();
        List<Transaction> allUnmodifiedSince = new ArrayList<>();

        for (Transaction transaction : allTransactions) {
            if (transaction.getLastUpdateTime().compareTo(date) < 0) {
                allUnmodifiedSince.add(transaction);
            }
        }

        return allUnmodifiedSince;
    }

    //    @Override
    protected List<Transaction> doFindAll() {

        try {
            final Set<byte[]> keys = RedisHelper.execute(jedisPool, jedis -> {
                if (isSupportScan) {
                    List<String> allKeys = new ArrayList<>();
                    String cursor = RedisHelper.SCAN_INIT_CURSOR;
                    ScanParams scanParams = RedisHelper.buildDefaultScanParams(keyPrefix + "*", fetchKeySize);
                    do {
                        ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                        allKeys.addAll(scanResult.getResult());
                        cursor = scanResult.getStringCursor();
                    } while (!cursor.equals(RedisHelper.SCAN_INIT_CURSOR));

                    Set<byte[]> allKeySet = new HashSet<>();

                    for (String key : allKeys) {
                        allKeySet.add(key.getBytes());
                    }
                    logger.info(String.format("find all key by scan command with pattern:%s allKeySet.size()=%d", keyPrefix + "*", allKeySet.size()));
                    return allKeySet;
                } else {
                    return jedis.keys((keyPrefix + "*").getBytes());
                }

            });


            return RedisHelper.execute(jedisPool, jedis -> {

                Pipeline pipeline = jedis.pipelined();

                for (final byte[] key : keys) {
                    pipeline.hgetAll(key);
                }
                List<Object> result = pipeline.syncAndReturnAll();

                List<Transaction> list = new ArrayList<>();
                for (Object data : result) {
                    if (data != null && ((Map<byte[], byte[]>) data).size() > 0) {
                        list.add(ExpandTransactionSerializer.deserialize(serializer, (Map<byte[], byte[]>) data));
                    }
                }
                return list;
            });

        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }
}
