package io.mycat.plan.common.item.function.strfunc;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import io.mycat.backend.mysql.CharsetUtil;
import io.mycat.plan.common.field.Field;
import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.primary.ItemIntFunc;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.List;


public class ItemFuncOrd extends ItemIntFunc {
    private String mysqlCharset;

    public ItemFuncOrd(List<Item> args, int charsetIndex) {
        super(args);
        this.mysqlCharset = CharsetUtil.getCharset(charsetIndex);
        this.charsetIndex = charsetIndex;
    }

    @Override
    public final String funcName() {
        return "ord";
    }

    @Override
    public BigInteger valInt() {
        String arg0 = args.get(0).valStr();
        char[] leftmost = new char[]{arg0.charAt(0)};
        try {
            byte[] bytes = new String(leftmost).getBytes(CharsetUtil.getJavaCharset(mysqlCharset));
            long res = 0L;
            for (byte aByte : bytes) {
                res = (aByte < 0 ? aByte + 256 : aByte) + res * 256L;
            }
            return BigInteger.valueOf(res);
        } catch (UnsupportedEncodingException e) {
            return BigInteger.ZERO;
        }
    }

    @Override
    public SQLExpr toExpression() {
        SQLMethodInvokeExpr method = new SQLMethodInvokeExpr(funcName());
        for (Item arg : args) {
            method.addParameter(arg.toExpression());
        }
        return method;
    }

    @Override
    protected Item cloneStruct(boolean forCalculate, List<Item> calArgs, boolean isPushDown, List<Field> fields) {
        List<Item> newArgs = null;
        if (!forCalculate)
            newArgs = cloneStructList(args);
        else
            newArgs = calArgs;
        return new ItemFuncOrd(newArgs, charsetIndex);
    }
}
