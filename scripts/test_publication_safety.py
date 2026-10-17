import unittest

from check_publication_safety import content_violation, path_violation


class PublicationSafetyTest(unittest.TestCase):
    def test_rejects_android_archive_and_signing_material(self):
        self.assertEqual(path_violation("app/libs/runtime.aar"), "forbidden-package-or-key")
        self.assertEqual(path_violation("signing/release.jks"), "forbidden-package-or-key")

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
        self.assertEqual(content_violation("manifest.json", content), "identifying-name-field")

    def test_rejects_unmasked_name_associated_with_participant_id(self):
        content = b'{"participant_id":"ID-01","name":"UNMASKED_VALUE"}'
        self.assertEqual(content_violation("manifest.json", content), "unmasked-participant-name")

    def test_rejects_csv_name_column_not_matching_pseudonymous_id(self):
        content = b"participant_id,name\nID-01,UNMASKED_VALUE\n"
        self.assertEqual(content_violation("participants.csv", content), "unmasked-participant-name")


if __name__ == "__main__":
    unittest.main()
