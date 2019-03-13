package org.seckill.service.impl;

import org.apache.commons.collections.MapUtils;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.entity.Seckill;
import org.seckill.exception.RepeatkillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.seckill.util.MD5Util;
import org.seckill.util.dto.Result;
import org.seckill.util.enums.ResultEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeckillServiceImpl implements SeckillService {
    private final static Logger logger = LoggerFactory.getLogger(SeckillServiceImpl.class);

    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    @Autowired
    private RedisDao redisDao;

    public List<Seckill> getSeckillList() {

        return seckillDao.queryAll(0, 10);
    }

    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    /**
     * 秒杀开启时输出秒杀接口地址
     * 否则输出系统时间和秒杀时间
     *
     * @param seckillId
     */
    public Exposer exportSeckillUrl(long seckillId) {
        //优化点：缓存优化;超时的基础上维护一致性。
        Date now = new Date();
        //1.从redis中拿数据
        Seckill seckill = redisDao.getSeckill(seckillId);
        if (seckill == null) {
            //2.查询秒杀数据
            seckill = seckillDao.queryById(seckillId);
            if (seckill == null) {
                return new Exposer(false, seckillId);
            } else if (seckill != null) {
                //3.将数据放入缓存中。
                redisDao.putSeckill(seckill);
            }
        }
        Exposer exposer;
        if (now.getTime() < seckill.getCreateTime().getTime() ||
                now.getTime() > seckill.getEndTime().getTime()) {
            exposer = new Exposer(false, seckillId, now.getTime(), seckill.getStartTime().getTime(), seckill.getEndTime().getTime());

        } else {
            String md5 = MD5Util.getMdStr(seckillId);
            exposer = new Exposer(true, md5, seckillId);

        }
        return exposer;
    }


    /**
     * 执行秒杀操作
     * 秒杀验证用户信息和md5值
     *
     * @param seckillId
     * @param userPhone
     * @param md5
     */
    @Transactional
    public Result executeSeckill(long seckillId, long userPhone, String md5) {
        String realKey = MD5Util.getMdStr(seckillId);
        if (!realKey.equals(md5)) {
            throw new SeckillException(ResultEnum.MD5_VERIFY_ERROR);
        }
        //减库存
        int i = seckillDao.reduceNumber(seckillId, new Date());
        if (i == 0) {
            throw new SeckillCloseException(ResultEnum.SECKILL_CLOSE);
        }
        //插入秒杀成功表
        int j = successKilledDao.insertSuccessKilled(seckillId, userPhone);
        if (j == 0) {
            throw new RepeatkillException(ResultEnum.REPEAT_SECKILL_ERROR);
        }
        return new Result(ResultEnum.SECKILL_SUCCESS);
    }

    /**
     * 使用存储过程,
     * 执行秒杀操作
     * 秒杀验证用户信息和md5值
     *
     * @param seckillId
     * @param phone
     * @param md5
     * @return
     */
    public Result executeSeckillProducedure(long seckillId, long phone, String md5) {
        String realKey = MD5Util.getMdStr(seckillId);
        if (!realKey.equals(md5)) {
            throw new SeckillException(ResultEnum.MD5_VERIFY_ERROR);
        }
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("seckillId", seckillId);
        map.put("phone", phone);
        map.put("killTime", new Date());
        map.put("result", null);
        //执行存储过程,result被复制
        try {
            seckillDao.killByProcedure(map);
            //获取result.
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                return new Result(ResultEnum.SECKILL_SUCCESS);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new SeckillException(ResultEnum.UNKNOW_ERROR);
        }
        return null;
    }
}