"""처리 원장(D8) 테스트. 실행: python3 -m unittest tests.test_ledger -v  (build/에서)

- 행 ID 재생성 동일(완료 기준 2·6): 같은 입력으로 DB를 두 번 만들면 바이트까지 같다, 추출도 다시 해도 같다
- 각주 펼침(완료 기준 2·6): '공통 제공 항목*', 'AI T 서비스*' 참조 아래 정의가 붙는다
- 중복 0(완료 기준 6): 행 ID·조각 ID
- 태그 대조(완료 기준 3): 쪽마다 PDF 태그의 표 행(TR) 수 = 그 쪽에 걸친 원장 행 수
- 고정점: 설계 문서가 인용하는 행(옛 첨부5-0119 → 첨부5-0124, 옛 첨부5-0130 → 첨부5-0137)
DB가 없으면 먼저 python3 ledger/build_ledger.py 를 실행한다.
"""
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
sys.path.insert(0, str(ROOT / "ledger"))
import build_ledger as bl  # noqa: E402
import extract as ex  # noqa: E402

DB = P.LEDGER_DB


def con():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


class Rebuild(unittest.TestCase):
    def test_db_bytes_identical(self):
        """캐시된 추출 결과로 DB를 두 번 만들면 파일이 바이트까지 같다(행 ID 고정)."""
        pdfs = bl.find_pdfs()
        ov, la = bl.load_overrides()
        results = {n: (*bl.extract_cached(n, p, overrides=ov.get(n), lines_add=la.get(n)), )
                   for n, p in pdfs.items()}
        results = {n: (s, pdfs[n], r) for n, (s, r) in results.items()}
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.db", Path(tmp) / "b.db"
            for path in (a, b):
                v, rows, segs, un, _ = bl.build_records(results)
                bl.write_db(path, v, rows, segs, un)
            self.assertEqual(sha(a), sha(b))
            ids = [r[0] for r in sqlite3.connect(a).execute("SELECT row_id FROM ledger_rows ORDER BY row_id")]
        self.assertEqual(ids, [r[0] for r in con().execute("SELECT row_id FROM ledger_rows ORDER BY row_id")])

    def test_extract_again_same(self):
        """캐시 없이 다시 뽑아도 같은 결과(작은 첨부 둘: 표 2개인 첨부10, 열 2개인 첨부6)."""
        pdfs = bl.find_pdfs()
        ov, la = bl.load_overrides()
        for n in (6, 10):
            _, cached = bl.extract_cached(n, pdfs[n], overrides=ov.get(n), lines_add=la.get(n))
            fresh = json.loads(json.dumps(ex.extract(pdfs[n], overrides=ov.get(n), lines_add=la.get(n)),
                                          ensure_ascii=False))
            self.assertEqual(cached, fresh, f"첨부{n}")


class Coverage(unittest.TestCase):
    def test_all_attachments(self):
        c = con()
        atts = {r[0]: r[1] for r in c.execute("SELECT attachment, count(*) FROM ledger_rows GROUP BY attachment")}
        self.assertEqual(set(atts), {f"첨부{n}" for n in range(1, 11)})
        self.assertTrue(all(v > 0 for v in atts.values()))
        vers = c.execute("SELECT count(*), count(DISTINCT file_sha256) FROM ledger_versions").fetchone()
        self.assertEqual(tuple(vers), (10, 10))

    def test_no_duplicates(self):
        c = con()
        self.assertEqual(c.execute("SELECT count(*) - count(DISTINCT row_id) FROM ledger_rows").fetchone()[0], 0)
        self.assertEqual(c.execute("SELECT count(*) - count(DISTINCT segment_id) FROM ledger_segments")
                         .fetchone()[0], 0)

    def test_nothing_silently_dropped(self):
        """행에 안 들어간 칸은 0이고, 표 밖 글은 목록에 남는다."""
        c = con()
        kinds = dict(c.execute("SELECT kind, count(*) FROM ledger_unextracted GROUP BY kind").fetchall())
        self.assertNotIn("UNMAPPED_CELL", kinds)
        self.assertIn("OUTSIDE_TEXT", kinds)   # 첨부8 11쪽 위치정보 관리책임자(표 밖)

    def test_tag_structure_matches(self):
        """PDF 태그의 표 행(TR) 수와 쪽별로 맞는다: 쪽 안 행 나누기가 Word 원본 구조와 같다."""
        rep = json.loads((P.LEDGER_CHECK / "자동점검.json").read_text(encoding="utf-8"))
        for att, v in rep["첨부별"].items():
            self.assertTrue(v["TR=행+이음"], att)
            self.assertEqual(v["태그와 행 수 다른 쪽"], [], att)


class Footnotes(unittest.TestCase):
    def test_common_items_expanded(self):
        c = con()
        rows = c.execute("""SELECT p.row_id, s.term, s.text FROM ledger_segments s
                            JOIN ledger_segments p ON p.segment_id = s.parent_id
                            WHERE s.seg_type='FOOTNOTE_DEF' AND p.text LIKE '%공통 제공 항목*%'""").fetchall()
        self.assertGreaterEqual(len(rows), 4)
        for rid, term, text in rows:
            self.assertEqual(term, "공통제공항목")
            self.assertTrue(text.startswith("공통 제공 항목은"), rid)
            self.assertIn("연계정보(CI)", text)

    def test_ai_service_expanded(self):
        c = con()
        n = c.execute("""SELECT count(*) FROM ledger_segments s JOIN ledger_segments p ON p.segment_id=s.parent_id
                         WHERE s.seg_type='FOOTNOTE_DEF' AND p.text LIKE '%AI T 서비스*%'
                         AND s.text LIKE '%서비스 개요%'""").fetchone()[0]
        self.assertEqual(n, 3)

    def test_inline_marks_not_paired(self):
        """'(*유튜브 …)'처럼 별표 뒤에 바로 글이 오는 표시는 각주 참조로 보지 않는다(엉뚱한 정의와 짝짓지 않음)."""
        c = con()
        n = c.execute("""SELECT count(*) FROM ledger_segments WHERE seg_type='FOOTNOTE_DEF'
                         AND row_id='첨부7-0004'""").fetchone()[0]
        self.assertEqual(n, 0)

    def test_split_cell(self):
        segs = bl.split_cell("- 공통 제공 항목*, 부가서비스\n정보\n* 공통 제공 항목은 성명\n※ 비고")
        self.assertEqual([s["type"] for s in segs], ["BULLET", "FOOTNOTE", "NOTE"])
        self.assertEqual(segs[0]["text"], "- 공통 제공 항목*, 부가서비스 정보")


class Anchors(unittest.TestCase):
    def fields(self, rid):
        return dict(json.loads(con().execute("SELECT fields_json FROM ledger_rows WHERE row_id=?", (rid,))
                               .fetchone()[0]))

    def test_t_membership_sk_planet(self):
        """설계 문서의 예(옛 첨부5-0119): T 멤버십 → SK 플래닛 판촉광고 목적 제공."""
        f = self.fields("첨부5-0124")
        self.assertEqual(f["구분 (서비스명)"], "T 멤버십")
        self.assertEqual(f["제공받는 자"], "SK 플래닛㈜")
        self.assertIn("판촉광고", f["제공받는 자의 이용 목적"])
        self.assertIn("이벤트/프로모션", f["제공받는 자의 이용 목적"])   # 26쪽으로 넘어간 글까지 이어짐

    def test_table_in_table(self):
        """옛 첨부5-0130(표 안의 표): 네 열에 걸친 한 칸으로 글이 섞이지 않는다."""
        c = con()
        f, merged = c.execute("SELECT fields_json, merged_json FROM ledger_rows WHERE row_id='첨부5-0137'").fetchone()
        f = dict(json.loads(f))
        self.assertEqual(json.loads(merged), ["제공받는 자~제공받는 자의 보유 및 이용 기간"])
        self.assertTrue(f["제공받는 자~제공받는 자의 보유 및 이용 기간"].startswith("[수집되는 개인정보의 판매자 제공]\n상품에"))

    def test_two_level_header(self):
        f = self.fields("첨부4-0002")
        self.assertEqual(f["위탁 업무 내용 > 재위탁 협력 회사"], "씨앤토트플러스㈜")
        self.assertEqual(self.fields("첨부4-0001")["위탁 업무 내용"], "이동전화/IoT 서비스의 고객 관리 시스템 운영")

    def test_two_tables(self):
        c = con()
        t = c.execute("""SELECT table_no, table_title, count(*) FROM ledger_rows WHERE attachment='첨부10'
                         GROUP BY table_no ORDER BY table_no""").fetchall()
        self.assertEqual([(x[0], x[1]) for x in t], [(1, "[추가적인 이용 시]"), (2, "[추가적인 제공 시]")])

    def test_overrides_applied(self):
        """쪽경계_수정.json: 28쪽 빠진 선(T 우주 LITE / Bill letter), 34쪽 새 행."""
        c = con()
        svc = dict(c.execute("SELECT row_id, service FROM ledger_rows WHERE row_id IN "
                             "('첨부5-0141','첨부5-0142')").fetchall())
        self.assertEqual(svc, {"첨부5-0141": "T 우주 LITE", "첨부5-0142": "Bill letter"})
        f = self.fields("첨부5-0185")
        self.assertEqual(f["제공받는 자의 이용 목적"], "회선 간 동일 명의 확인")

    def test_bullet_back_to_its_cell(self):
        """칸 왼쪽 선을 넘어 놓인 글머리표는 오른쪽 글의 칸으로(첨부6 '이니셜(initial) •' → '이니셜(initial)')."""
        c = con()
        svc = dict(c.execute("SELECT row_id, service FROM ledger_rows WHERE row_id IN ('첨부6-0012','첨부6-0013')"))
        self.assertEqual(svc, {"첨부6-0012": "이니셜(initial)", "첨부6-0013": "ASUM"})
        self.assertEqual(c.execute("SELECT count(*) FROM ledger_rows WHERE service LIKE '%•%'").fetchone()[0], 0)

    def test_page_split_rows_joined(self):
        """쪽에서 잘린 행이 한 행으로 이어진다(글 이어짐·빈 칸 근거)."""
        self.assertIn("㈜헥토파이낸셜", self.fields("첨부5-0054")["제공받는 자"])        # 9→10쪽, 쉼표로 끝남
        self.assertIn("이용자의 개인정보를 파기하는\n시점까지 보유함.", self.fields("첨부1-0104")["보유 및 이용 기간"])  # 25→26쪽


class Rules(unittest.TestCase):
    def test_unfinished(self):
        for t in ("㈜쿠콘,\n㈜하이픈코퍼레이션,", "제15조(개인정보의", "바로코퍼레이션㈜, ㈜", "통신과금서비스(휴대폰"):
            self.assertTrue(ex.unfinished(t), t)
        for t in ("서비스 해지 시까지", "엠즈씨드 주식회사", "제1항", "삼성SDS㈜"):
            self.assertFalse(ex.unfinished(t), t)

    def test_continues(self):
        self.assertTrue(ex.continues("단, 법령에서 따로 정하는 경우"))
        self.assertFalse(ex.continues("단말기 정보"))


if __name__ == "__main__":
    unittest.main()
