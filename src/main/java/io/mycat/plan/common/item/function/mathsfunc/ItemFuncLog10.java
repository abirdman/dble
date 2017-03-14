package io.mycat.plan.common.item.function.mathsfunc;

import java.math.BigDecimal;
import java.util.List;

import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.ItemFunc;
import io.mycat.plan.common.item.function.primary.ItemDecFunc;


public class ItemFuncLog10 extends ItemDecFunc {

	public ItemFuncLog10(List<Item> args) {
		super(args);
	}

	@Override
	public final String funcName() {
		return "log10";
	}

	public BigDecimal valReal() {
		double value = args.get(0).valReal().doubleValue();

		if ((nullValue = args.get(0).nullValue))
			return BigDecimal.ZERO;
		if (value <= 0.0) {
			signalDivideByNull();
			return BigDecimal.ZERO;
		}
		return new BigDecimal(Math.log10(value));
	}
	
	@Override
	public ItemFunc nativeConstruct(List<Item> realArgs) {
		return new ItemFuncLog10(realArgs);
	}
}