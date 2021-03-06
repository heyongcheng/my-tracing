package cn.sumpay.tracing.dubbo.filter;

import cn.sumpay.tracing.TracerAttachment;
import cn.sumpay.tracing.TracerFactory;
import cn.sumpay.tracing.TracerConfig;
import cn.sumpay.tracing.context.TracingContext;
import cn.sumpay.tracing.util.JsonUtil;
import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.*;
import io.opentracing.NoopTracer;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;

/**
 * @author heyc
 */
@Activate(group = Constants.CONSUMER)
public class TracerConsumerFilter implements Filter{

    private static final Logger LOG = LoggerFactory.getLogger(TracerConsumerFilter.class);

    /**
     * 拦截记录dubbo请求
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        /** 不记录 **/
        Tracer tracer = TracerFactory.DEFAULT.getTracer();
        if (!TracerConfig.ENABLE || tracer == null || tracer instanceof NoopTracer){
            return invoker.invoke(invocation);
        }
        Span span = null;
        try {
            /** 构建span **/
            String application = RpcContext.getContext().getUrl().getParameter("application");
            String operationName = application + "_" + invocation.getMethodName();
            Tracer.SpanBuilder spanBuilder = tracer.buildSpan(operationName).withTag(Tags.SPAN_KIND.getKey(),Tags.SPAN_KIND_CLIENT);
            Span activeSpan = TracingContext.getSpan();
            if (activeSpan != null) {
                spanBuilder.asChildOf(activeSpan);
            }
            span = spanBuilder.startManual();
            /** 属性传递 **/
            attachTraceInfo(tracer, span, invocation);
        }catch (Exception e){
            LOG.error("构建span异常: {}",e.getMessage());
        }
        Result result = null;
        try {
            result = invoker.invoke(invocation);
        }finally {
            try {
                if (span != null){
                    /** 添加属性 **/
                    if(TracerConfig.REQUEST){
                        span.setTag("request", JsonUtil.toJsonString(invocation.getArguments()));
                    }
                    if (TracerConfig.RESPONSE){
                        span.setTag("response",JsonUtil.toJsonString(result));
                    }
                    span.setTag(TracerAttachment.TYPE,"dubbo");
                    span.setTag(TracerAttachment.METHOD,invocation.getMethodName());
                    span.setTag("remoteIp",invoker.getUrl().getIp());
                    span.setTag("interface",invocation.getInvoker().getInterface().getName());
                    span.finish();
                }
            }catch (Exception e){
                LOG.error("END span ERROR: {}",e.getMessage());
            }
        }
        return result;
    }

    /**
     * attachTraceInfo
     * @param tracer
     * @param span
     * @param invocation
     */
    private void attachTraceInfo(Tracer tracer, Span span, final Invocation invocation) {
        tracer.inject(span.context(), Format.Builtin.TEXT_MAP, new TextMap() {
            @Override
            public void put(String key, String value) {
                invocation.getAttachments().put(key, value);
            }
            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                throw new UnsupportedOperationException("TextMapInjectAdapter should only be used with Tracer.inject()");
            }
        });
    }
}
