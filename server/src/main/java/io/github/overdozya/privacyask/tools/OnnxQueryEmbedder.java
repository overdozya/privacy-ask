package io.github.overdozya.privacyask.tools;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * KURE-v3 질의 임베딩(int8 ONNX, ONNX Runtime + HF tokenizer.json). 빌드가 만든 파일(build/emb/export_onnx.py)을 쓴다.
 * 방법은 문서 벡터와 같게: 앞에 `query: `, 토큰화(최대 1024, 특수 토큰 포함), 마지막 층 attention mask 평균 풀링, 앞 1024차원, 길이 1.
 * 모델은 처음 쓸 때(또는 warmup) 한 번 띄운다(파일 약 1.1GB, 네이티브 메모리). 세션은 여러 스레드가 같이 써도 된다.
 */
public final class OnnxQueryEmbedder implements QueryEmbedder, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OnnxQueryEmbedder.class);
    public static final String MODEL = "model_int8pc.onnx", TOKENIZER = "tokenizer.json", PREFIX = "query: ";
    static final int MAX_LEN = 1024;

    private final Path dir;
    private volatile Loaded loaded;
    private volatile String failed;

    private record Loaded(OrtEnvironment env, OrtSession session, HuggingFaceTokenizer tokenizer, long loadMs) {}

    public OnnxQueryEmbedder(Path dir) {
        this.dir = dir;
    }

    /** 파일이 있으면 만들고, 없으면 NONE. */
    public static QueryEmbedder of(Path dir) {
        if (dir == null || !Files.isRegularFile(dir.resolve(MODEL)) || !Files.isRegularFile(dir.resolve(TOKENIZER))) {
            log.warn("A6 질의 모델 없음({}): 안내서 검색은 글자 검색만으로 돈다", dir);
            return new Missing("질의 모델 파일 없음: " + dir);
        }
        return new OnnxQueryEmbedder(dir);
    }

    @Override
    public boolean available() {
        return failed == null;
    }

    @Override
    public String why() {
        return failed;
    }

    /** 모델을 띄운다(이미 떴으면 그대로). 띄운 ms. */
    public long load() {
        Loaded l = loaded;
        if (l != null) {
            return l.loadMs();
        }
        synchronized (this) {
            if (loaded == null) {
                long t = System.nanoTime();
                try {
                    System.setProperty("ai.djl.offline", "true");   // 토크나이저 네이티브는 jar 안의 것만(내려받지 않음)
                    HuggingFaceTokenizer tok = HuggingFaceTokenizer.newInstance(dir.resolve(TOKENIZER), Map.of(
                            "addSpecialTokens", "true", "truncation", "true", "maxLength", String.valueOf(MAX_LEN),
                            "padding", "false"));
                    OrtEnvironment env = OrtEnvironment.getEnvironment();
                    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
                    OrtSession s = env.createSession(dir.resolve(MODEL).toString(), so);
                    loaded = new Loaded(env, s, tok, (System.nanoTime() - t) / 1_000_000);
                    log.info("A6 질의 모델 띄움 {} ms ({})", loaded.loadMs(), dir);
                } catch (Exception | UnsatisfiedLinkError e) {
                    failed = "질의 모델을 띄우지 못함: " + e;
                    log.warn(failed);
                    throw new IllegalStateException(failed, e);
                }
            }
            return loaded.loadMs();
        }
    }

    @Override
    public float[] embed(String query) {
        load();
        Loaded l = loaded;
        Encoding enc = l.tokenizer().encode(PREFIX + query);
        long[] ids = enc.getIds();
        long[] mask = enc.getAttentionMask();
        try (OnnxTensor a = OnnxTensor.createTensor(l.env(), new long[][] {ids});
             OnnxTensor b = OnnxTensor.createTensor(l.env(), new long[][] {mask});
             OrtSession.Result r = l.session().run(Map.of("input_ids", a, "attention_mask", b),
                     Set.of("last_hidden_state"))) {
            OnnxTensor h = (OnnxTensor) r.get(0);
            long[] shape = h.getInfo().getShape();   // [1, seq, hidden]
            int seq = (int) shape[1], hidden = (int) shape[2];
            return pool(h.getFloatBuffer(), mask, seq, hidden);
        } catch (OrtException e) {
            throw new IllegalStateException("질의 임베딩 실패: " + e.getMessage(), e);
        }
    }

    /** attention mask 평균 풀링 → 앞 DIM → 길이 1. */
    static float[] pool(FloatBuffer h, long[] mask, int seq, int hidden) {
        double[] sum = new double[DIM];
        double n = 0;
        for (int t = 0; t < seq; t++) {
            if (mask[t] == 0) {
                continue;
            }
            n += 1;
            int base = t * hidden;
            for (int d = 0; d < DIM; d++) {
                sum[d] += h.get(base + d);
            }
        }
        float[] v = new float[DIM];
        double norm = 0;
        for (int d = 0; d < DIM; d++) {
            double x = sum[d] / Math.max(n, 1e-9);
            v[d] = (float) x;
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        for (int d = 0; d < DIM; d++) {
            v[d] = (float) (v[d] / norm);
        }
        return v;
    }

    @Override
    public synchronized void close() throws OrtException {
        if (loaded != null) {
            loaded.session().close();
            loaded.tokenizer().close();
            loaded = null;
        }
    }

    /** 파일이 없을 때. */
    record Missing(String why) implements QueryEmbedder {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public float[] embed(String query) {
            throw new IllegalStateException(why);
        }
    }
}
