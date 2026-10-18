import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from check_publication_safety import (
    HISTORICAL_ALLOWED_AAR_PATH,
    HISTORICAL_ALLOWED_AAR_SHA256,
    ROOT,
    content_violation,
    historical_path_violation,
    inspect_history,
    path_violation,
)


class PublicationSafetyTest(unittest.TestCase):
    def test_worktree_rejects_all_android_archives_and_signing_material(self):
        self.assertEqual(
            path_violation("app/libs/runtime.aar"), "forbidden-package-or-key"
        )
        self.assertEqual(
            path_violation("signing/release.jks"), "forbidden-package-or-key"
        )

    def test_history_rejects_unapproved_or_changed_archives(self):
        self.assertEqual(
            historical_path_violation(
                "android/wear/libs/samsung-health-sensor.aar",
                HISTORICAL_ALLOWED_AAR_SHA256,
            ),
            "forbidden-package-or-key",
        )
        self.assertEqual(
            historical_path_violation(HISTORICAL_ALLOWED_AAR_PATH, "0" * 64),
            "forbidden-package-or-key",
        )
        self.assertEqual(
            historical_path_violation(HISTORICAL_ALLOWED_AAR_PATH),
            "forbidden-package-or-key",
        )

    def test_history_rejects_the_approved_blob_at_an_unapproved_alias_path(self):
        objects = subprocess.check_output(
            ["git", "rev-list", "--objects", "--all"], cwd=ROOT, text=True
        )
        blob_id = next(
            (
                line.split(" ", 1)[0]
                for line in objects.splitlines()
                if line.endswith(f" {HISTORICAL_ALLOWED_AAR_PATH}")
            ),
            None,
        )
        if blob_id is None:
            self.skipTest("approved historical Sherpa blob is not reachable")

        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            subprocess.run(
                ["git", "init", "--quiet", "-b", "main", str(repository)],
                check=True,
            )
            alternates = repository / ".git" / "objects" / "info" / "alternates"
            alternates.write_text(str(ROOT / ".git" / "objects"), encoding="utf-8")

            def make_tree(entries: list[tuple[str, str, str, str]]) -> str:
                tree_input = b"".join(
                    f"{mode} {kind} {object_id}\t{name}".encode() + b"\0"
                    for mode, kind, object_id, name in entries
                )
                return (
                    subprocess.check_output(
                        ["git", "mktree", "-z"],
                        cwd=repository,
                        input=tree_input,
                        text=False,
                    )
                    .decode("ascii")
                    .strip()
                )

            app_libs = make_tree(
                [("100644", "blob", blob_id, Path(HISTORICAL_ALLOWED_AAR_PATH).name)]
            )
            app = make_tree([("040000", "tree", app_libs, "libs")])
            wear_libs = make_tree(
                [("100644", "blob", blob_id, "samsung-health-sensor.aar")]
            )
            wear = make_tree([("040000", "tree", wear_libs, "libs")])
            android = make_tree([("040000", "tree", wear, "wear")])
            root_tree = make_tree(
                [("040000", "tree", android, "android"), ("040000", "tree", app, "app")]
            )
            commit = subprocess.check_output(
                [
                    "git",
                    "-c",
                    "user.name=Publication Test",
                    "-c",
                    "user.email=publication-test@example.invalid",
                    "commit-tree",
                    root_tree,
                    "-m",
                    "alias regression",
                ],
                cwd=repository,
                text=True,
            ).strip()
            subprocess.run(
                ["git", "update-ref", "refs/heads/main", commit],
                cwd=repository,
                check=True,
            )

            with patch("check_publication_safety.ROOT", repository):
                findings = inspect_history()

        self.assertIn(
            ("forbidden-package-or-key", "android/wear/libs/samsung-health-sensor.aar"),
            findings,
        )

    def test_rejects_raw_participant_source_directories(self):
        self.assertEqual(
            path_violation("DataTrimmedPartisipan/ID-01/session.csv"),
            "restricted-participant-data-path",
        )

    def test_allows_source_and_pseudonymous_identifier(self):
        self.assertIsNone(path_violation("app/src/main/java/Detector.kt"))
        self.assertIsNone(
            content_violation(
                "manifest.json",
                b'{"participant_id":"ID-01","name":"ID-01"}',
            )
        )

    def test_rejects_explicit_participant_name_field(self):
        key = "participant" + "_name"
        content = ('{"' + key + '":"UNMASKED_VALUE"}').encode()
        self.assertEqual(
            content_violation("manifest.json", content), "identifying-name-field"
        )

    def test_rejects_unmasked_name_associated_with_participant_id(self):
        content = b'{"participant_id":"ID-01","name":"UNMASKED_VALUE"}'
        self.assertEqual(
            content_violation("manifest.json", content), "unmasked-participant-name"
        )

    def test_rejects_csv_name_column_not_matching_pseudonymous_id(self):
        content = b"participant_id,name\nID-01,UNMASKED_VALUE\n"
        self.assertEqual(
            content_violation("participants.csv", content), "unmasked-participant-name"
        )


if __name__ == "__main__":
    unittest.main()
