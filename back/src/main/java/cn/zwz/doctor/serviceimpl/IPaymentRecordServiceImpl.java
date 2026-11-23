package cn.zwz.doctor.serviceimpl;

import cn.zwz.doctor.entity.PaymentRecord;
import cn.zwz.doctor.mapper.PaymentRecordMapper;
import cn.zwz.doctor.service.IPaymentRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付流水接口实现
 * @author 郑为中
 */
@Service
@Transactional
public class IPaymentRecordServiceImpl extends ServiceImpl<PaymentRecordMapper, PaymentRecord> implements IPaymentRecordService {

}
