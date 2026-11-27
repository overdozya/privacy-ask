package io.github.overdozya.privacyask.tools;

/** 질의 → 1024차원 길이 1 벡터(문서 벡터와 같은 모델). 모델이 없으면 available() 거짓이고 embed는 부르지 않는다. */
public interface QueryEmbedder {
    int DIM = 1024;

    boolean available();

    /** 쓸 수 없는 까닭(available이면 null). */
    String why();

    float[] embed(String query);

    /** 모델이 없는 것. */
    QueryEmbedder NONE = new QueryEmbedder() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String why() {
            return "질의 모델 없음";
        }

        @Override
        public float[] embed(String query) {
            throw new IllegalStateException(why());
        }
    };
}
