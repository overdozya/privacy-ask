"""오프라인 합성 응답 테스트. 실제 법령을 수신했다는 증거가 아님."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import law_api as law
import package_data
import prepare_dataset


CATALOG = {a["guide_id"]: a for a in law.load_json(law.ROOT / "api_catalog.json")["apis"]}


def row(name="개인정보 보호법", serial="900001", date="20260911"):
    return {"법령명한글": name, "법령일련번호": serial, "법령ID": "TEST-ID", "시행일자": date}


class FakeClient:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    def fetch(self, api, params):
        self.calls.append((api["guide_id"], law.prepare_request(api, params)))
        data = next(self.responses)
        if isinstance(data, Exception):
            raise data
        law.check_api_error(data)
        raw = json.dumps(data, ensure_ascii=False).encode()
        return data, raw, {"synthetic_test_fixture": True, "params": params}


class LawApiTests(unittest.TestCase):
    def test_public_http_200_auth_failure_is_not_law_data(self):
        raw = (law.ROOT / "samples/public-test-response.txt").read_bytes()
        with self.assertRaises(law.AuthenticationError):
            law.decode_response(raw)

    def test_html_and_scalar_response_are_rejected(self):
        for raw in (b"<html>login</html>", b'"ok"', b"{}"):
            with self.subTest(raw=raw), self.assertRaises(law.ApiError):
                law.decode_response(raw)

    def test_error_word_in_law_text_is_not_an_api_error(self):
        law.check_api_error({"법령": {"조문내용": "오류 발생 또는 인증 실패 시"}})

    def test_single_record_and_array_are_both_readable(self):
        for value in (row(), [row()]):
            data = {"LawSearch": {"totalCnt": "1", "law": value}}
            self.assertEqual(law.search_records(data, "law"), [row()])

    def test_exact_title_not_first_partial_match(self):
        records = [row("개인정보 보호법 시행령", "900002"), row("개인정보보호법")]
        self.assertEqual(law.choose_current(records, "개인정보 보호법", "law", "20261030")["법령일련번호"], "900001")

    def test_future_and_ambiguous_versions(self):
        future = row(serial="900002", date="20270309")
        self.assertEqual(law.choose_current([future, row()], "개인정보 보호법", "law", "20261030"), row())
        with self.assertRaises(law.ApiError):
            law.choose_current([row(), row(serial="900003")], "개인정보 보호법", "law", "20261030")

    def test_unknown_date_or_missing_exact_title(self):
        for records in ([row(date="")], [row("개인정보 보호법 시행령")]):
            with self.subTest(records=records), self.assertRaises(law.ApiError):
                law.choose_current(records, "개인정보 보호법", "law", "20261030")

    def test_branch_article_encoding(self):
        for value, expected in [("34", "003400"), ("48-3", "004803"), ("제37조의21", "003721"), ("005808", "005808")]:
            self.assertEqual(law.article_code(value), expected)
        for value in ("제23조제2항", "-1", "0", "10000", "48-100"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                law.article_code(value)

    def test_mst_effective_date_pair_and_current_id(self):
        api = CATALOG["lsEfYdInfoGuide"]
        self.assertEqual(law.prepare_request(api, {"ID": "123"})["type"], "JSON")
        self.assertEqual(law.prepare_request(api, {"MST": "123", "efYd": "20260911"})["efYd"], "20260911")
        for params in ({"MST": "123"}, {"MST": "123", "efYd": "wrong"}, {"MST": "123", "ID": "456", "efYd": "20260911"}):
            with self.subTest(params=params), self.assertRaises(law.ApiError):
                law.prepare_request(api, params)

    def test_administrative_rule_id_is_serial(self):
        record = {"행정규칙일련번호": "2100000999999", "행정규칙ID": "8888"}
        self.assertEqual(law.body_params(record, "admrul"), {"ID": "2100000999999"})

    def test_html_only_api_and_unsupported_parameters_rejected(self):
        for gid, params in [("lsHstListGuide", {}), ("lsDayJoRvsListGuide", {"display": "100"}),
                            ("lsEfYdListGuide", {"display": "101"}), ("lsEfYdListGuide", {"OC": "secret"})]:
            with self.subTest(gid=gid), self.assertRaises(law.ApiError):
                law.prepare_request(CATALOG[gid], params)

    def test_all_json_examples_fit_documented_parameters(self):
        for api in CATALOG.values():
            if not api["json_supported"]:
                continue
            params = {k: ("20260911" if k == "efYd" else "900001") if str(v).startswith("{{") else v
                      for k, v in api["example_params"].items()}
            with self.subTest(api=api["guide_id"]):
                self.assertEqual(law.prepare_request(api, params)["type"], "JSON")

    def test_pagination_reads_second_page_before_title_selection(self):
        page1 = [row(name=f"다른 법령 {i}", serial=str(1000 + i)) for i in range(100)]
        client = FakeClient([{"LawSearch": {"totalCnt": 101, "law": page1}},
                             {"LawSearch": {"totalCnt": 101, "law": row()}}])
        with tempfile.TemporaryDirectory() as directory:
            records = law.search_all(client, CATALOG["lsEfYdListGuide"], "개인정보 보호법", "law", directory)
        self.assertEqual(len(records), 101)
        self.assertEqual(client.calls[1][1]["page"], "2")
        self.assertEqual(law.choose_current(records, "개인정보 보호법", "law", "20261030"), row())

    def test_repeat_page_and_partial_result_not_silently_accepted(self):
        page = {"LawSearch": {"totalCnt": 101, "law": [row(serial=str(i)) for i in range(100)]}}
        for responses in ([page, page], [{"LawSearch": {"totalCnt": 2, "law": row()}}]):
            with tempfile.TemporaryDirectory() as directory, self.assertRaises(law.ApiError):
                law.search_all(FakeClient(responses), CATALOG["lsEfYdListGuide"], "개인정보 보호법", "law", directory)

    def test_successful_collect_keeps_source_and_body(self):
        docs = [{"slug": "privacy", "name": "개인정보 보호법", "kind": "law", "group": "core"}]
        body = {"법령": {"기본정보": {"법령명_한글": "개인정보 보호법"}, "조문": [{"조문내용": "합성 테스트 원문"}]}}
        client = FakeClient([{"LawSearch": {"totalCnt": 1, "law": row()}}, body])
        with tempfile.TemporaryDirectory() as directory, patch("sys.stdout", new_callable=io.StringIO):
            result = law.collect(client, CATALOG, docs, directory)
            self.assertEqual(result["status"], "downloaded")
            self.assertTrue((Path(directory) / "privacy/selected.json").exists())
            self.assertEqual(law.load_json(Path(directory) / "privacy/body/response.json"), body)
        self.assertEqual(client.calls[1][1]["MST"], "900001")
        self.assertEqual(client.calls[1][1]["efYd"], "20260911")

    def test_auth_failure_stops_collection_immediately(self):
        docs = [{"slug": "one", "name": "법령1", "kind": "law", "group": "core"},
                {"slug": "two", "name": "법령2", "kind": "law", "group": "core"}]
        client = FakeClient([law.AuthenticationError("등록 확인 필요")])
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(law.AuthenticationError):
                law.collect(client, CATALOG, docs, directory)
            self.assertEqual(law.load_json(Path(directory) / "manifest.json")["status"], "authentication_failed")
        self.assertEqual(len(client.calls), 1)

    def test_wrong_body_is_marked_for_review(self):
        with self.assertRaises(law.ApiError):
            law.validate_body({"법령": {"법령명_한글": "다른 법", "조문내용": "테스트"}}, row(), "law")

    def test_oc_is_sent_but_not_persisted_in_request_metadata(self):
        class Response:
            status = 200
            headers = {"Content-Type": "application/json;charset=UTF-8"}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self): return b'{"LawSearch":{"totalCnt":0}}'
        with patch("law_api.urlopen", return_value=Response()) as opener:
            _, _, metadata = law.Client("SYNTHETIC-SECRET", interval=0).fetch(CATALOG["lsEfYdListGuide"], {"query": "개인정보 보호법", "nw": 3})
        self.assertIn("SYNTHETIC-SECRET", opener.call_args.args[0].full_url)
        self.assertNotIn("SYNTHETIC-SECRET", json.dumps(metadata))
        self.assertEqual(metadata["params"]["OC"], "[REDACTED]")

    def test_local_credentials_and_environment_precedence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('# comment\nLAW_API_OC="TEST-ONLY"\n')
            with patch.dict("os.environ", {}, clear=True):
                self.assertEqual(law.load_oc(path), "TEST-ONLY")
            with patch.dict("os.environ", {"LAW_API_OC": "ENV-TEST"}, clear=True):
                self.assertEqual(law.load_oc(path), "ENV-TEST")

    def test_permission_page_is_reported_specifically(self):
        with self.assertRaises(law.ServicePermissionError):
            law.decode_response('<html>미신청된 목록/본문에 대한 접근입니다.</html>'.encode())

    def test_oc_echo_in_api_links_is_redacted_without_changing_law_text(self):
        original = {"법령상세링크": "/DRF/lawService.do?OC=SYNTHETIC-SECRET&target=eflaw",
                    "nested": [{"url": "https://www.law.go.kr/a?x=1&amp;OC=SYNTHETIC-SECRET&amp;id=2"}],
                    "조문내용": "제34조의 테스트 원문"}
        result = law.redact_credentials(original)
        self.assertNotIn("SYNTHETIC-SECRET", json.dumps(result))
        self.assertEqual(result["조문내용"], original["조문내용"])
        self.assertIn("&target=eflaw", result["법령상세링크"])

    def test_merged_relationship_targets_are_not_guessed(self):
        self.assertIsNone(prepare_dataset.scalar(["법령 A", "법령 B"]))
        self.assertIsNone(prepare_dataset.scalar([{"title": "법령 A"}]))
        self.assertEqual(prepare_dataset.scalar(["법령 A", "법령 A"]), "법령 A")

    def test_package_refuses_partial_or_auth_failed_collections(self):
        for status in ("partial", "authentication_failed", "in_progress"):
            with tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source"
                output = Path(directory) / "share.zip"
                law.save_json(source / "manifest.json", {"status": status, "documents": [{"name": "test"}]})
                with self.assertRaises(law.ApiError):
                    package_data.package(source, output)
                self.assertFalse(output.exists())

    def test_package_refuses_synthetic_data(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            output = Path(directory) / "share.zip"
            law.save_json(source / "manifest.json", {"status": "downloaded", "documents": [
                {"slug": "privacy", "name": "개인정보 보호법", "kind": "law", "group": "core", "status": "downloaded"}]})
            law.save_json(source / "privacy/body/response.json", {"fake": True})
            law.save_json(source / "privacy/body/request.json", {"synthetic_test_fixture": True})
            law.save_json(source / "privacy/selected.json", row())
            with self.assertRaises(law.ApiError):
                package_data.package(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
