package io.github.overdozya.privacyask.packages;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;

/** 스프링 진입점: 지금 스냅샷으로 꾸러미를 열고, 기준일마다 G2 목록을 한 번 만든다(스냅샷이 불변이라 날짜별로 들고 있어도 됨). */
public class PackageService {
    private final Knowledge knowledge;
    private final Map<LocalDate, ArticleList> lists = new ConcurrentHashMap<>();
    private volatile KnowledgeSnapshot listsFor;

    public PackageService(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    public PackageOpener opener() {
        return new PackageOpener(knowledge.snapshot());
    }

    public PackageOpener.Opened open(String packageVersion, Collection<PackageOpener.IssueRef> issues, LocalDate date) {
        return opener().open(packageVersion, issues, date);
    }

    public ArticleList articleList(LocalDate date) {
        KnowledgeSnapshot k = knowledge.snapshot();
        if (listsFor != k) {
            lists.clear();
            listsFor = k;
        }
        return lists.computeIfAbsent(date, d -> new ArticleList(k, d));
    }
}
