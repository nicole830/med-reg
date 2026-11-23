package cn.zwz.doctor.entity;

import cn.zwz.basics.baseClass.ZwzBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.Entity;
import javax.persistence.Table;
import java.math.BigDecimal;

/**
 * 挂号支付流水
 * @author 郑为中
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "a_payment_record")
@TableName("a_payment_record")
@ApiModel(value = "支付流水")
public class PaymentRecord extends ZwzBaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "挂号单ID")
    private String orderId;

    @ApiModelProperty(value = "付款人ID")
    private String userId;

    @ApiModelProperty(value = "付款人姓名")
    private String userName;

    @ApiModelProperty(value = "金额")
    private BigDecimal amount;

    /**
     * 0 支付成功 | 1 已退款
     */
    @ApiModelProperty(value = "流水状态")
    private Integer status;

    @ApiModelProperty(value = "支付流水号")
    private String payNo;

    @ApiModelProperty(value = "退款流水号")
    private String refundNo;

    @ApiModelProperty(value = "备注")
    private String remark;
}
