"""Generalized role-separation contract tests for the Adaptive Filesystem model.

Scope: synthetic contract tests, not production runtime integration.

Important distinction:
- belongs_to / role association is not the same as graph connectivity.
- An item may be TERA-associated without being FIRA-associated (check 1),
  while the FIRA coordination node itself must connect to TERA and WATA (check 8).
- The post-connection report is an explicit generated artifact. Merely declaring
  a schema or connecting nodes does not implicitly create that artifact.
"""
from copy import deepcopy
import unittest


def make_model():
    """A small, explicit graph of entities and typed associations."""
    return {
        "items": {
            "tera_plain": {"belongs_to": ["TERA"], "properties": [], "generation": None},
            "tera_with_properties": {
                "belongs_to": ["TERA"], "properties": ["p1"], "generation": None
            },
            "tera_with_generation": {
                "belongs_to": ["TERA"], "properties": [], "generation": "g1"
            },
            "wata_plain": {"belongs_to": ["WATA"], "properties": [], "generation": None},
            "fira_only": {"belongs_to": ["FIRA"], "properties": [], "generation": None},
            "tera_leaf": {"belongs_to": ["TERA", "AIRA"], "properties": [], "generation": None},
        },
        "nodes": {
            "TERA": {"connects_to": []},
            "FIRA": {"connects_to": ["TERA", "WATA"]},
            "WATA": {"connects_to": []},
            "AIRA": {"connects_to": []},
        },
    }


def post_connection_report(model):
    """Generate the explicit sixth output after the graph connection step."""
    nodes = model.get("nodes", {})
    required = ("TERA", "FIRA", "WATA", "AIRA")
    missing_nodes = [node for node in required if node not in nodes]
    fira_links = nodes.get("FIRA", {}).get("connects_to", [])
    return {
        "report_type": "post_connection",
        "generated": True,
        "required_nodes_present": not missing_nodes,
        "missing_nodes": missing_nodes,
        "fira_connections": deepcopy(fira_links),
        "fira_connected_to_tera_and_wata": all(x in fira_links for x in ("TERA", "WATA")),
        "schema_declared_is_not_report_generated": True,
    }


class RoleSeparationContractTests(unittest.TestCase):
    def setUp(self):
        self.model = make_model()
        self.items = self.model["items"]

    # 1. TERA membership must not be silently conflated with FIRA membership.
    def test_01_tera_association_is_not_fira_association(self):
        item = self.items["tera_plain"]
        self.assertIn("TERA", item["belongs_to"])
        self.assertNotIn("FIRA", item["belongs_to"])

    # 2. TERA-with-properties is not WATA-without-properties.
    def test_02_tera_properties_do_not_leak_to_plain_wata(self):
        tera_item = self.items["tera_with_properties"]
        wata_item = self.items["wata_plain"]
        self.assertIn("TERA", tera_item["belongs_to"])
        self.assertTrue(tera_item["properties"])
        self.assertIn("WATA", wata_item["belongs_to"])
        self.assertFalse(wata_item["properties"])

    # 3. TERA-with-generation is not WATA-without-generation.
    def test_03_tera_generation_does_not_leak_to_plain_wata(self):
        tera_item = self.items["tera_with_generation"]
        wata_item = self.items["wata_plain"]
        self.assertIn("TERA", tera_item["belongs_to"])
        self.assertIsNotNone(tera_item["generation"])
        self.assertIn("WATA", wata_item["belongs_to"])
        self.assertIsNone(wata_item["generation"])

    # 4. WATA membership must not be silently relabeled TERA.
    def test_04_wata_association_is_not_tera_association(self):
        item = self.items["wata_plain"]
        self.assertIn("WATA", item["belongs_to"])
        self.assertNotIn("TERA", item["belongs_to"])

    # 5. FIRA-only membership must not be collapsed into TERA or WATA.
    def test_05_fira_only_item_is_neither_tera_nor_wata(self):
        item = self.items["fira_only"]
        self.assertIn("FIRA", item["belongs_to"])
        self.assertNotIn("TERA", item["belongs_to"])
        self.assertNotIn("WATA", item["belongs_to"])

    # 6. A TERA-associated root can explicitly include its AIRA leaf-like part.
    def test_06_tera_item_is_associated_with_aira(self):
        item = self.items["tera_leaf"]
        self.assertIn("TERA", item["belongs_to"])
        self.assertIn("AIRA", item["belongs_to"])

    # 7. WATA membership must not be silently conflated with FIRA membership.
    def test_07_wata_association_is_not_fira_association(self):
        item = self.items["wata_plain"]
        self.assertIn("WATA", item["belongs_to"])
        self.assertNotIn("FIRA", item["belongs_to"])

    # 8. FIRA is the coordination node connected to both TERA and WATA.
    def test_08_fira_connects_to_tera_and_wata(self):
        links = self.model["nodes"]["FIRA"]["connects_to"]
        self.assertIn("TERA", links)
        self.assertIn("WATA", links)

    def test_09_post_connection_report_is_generated_and_checks_connections(self):
        report = post_connection_report(self.model)
        self.assertTrue(report["generated"])
        self.assertTrue(report["required_nodes_present"])
        self.assertTrue(report["fira_connected_to_tera_and_wata"])
        self.assertEqual(report["report_type"], "post_connection")

    def test_10_declaring_schema_alone_does_not_claim_report_generation(self):
        # A schema is a specification; a report exists only after this generator runs.
        schema_only = {"schema_declared": True}
        self.assertNotIn("post_connection_report", schema_only)
        report = post_connection_report(self.model)
        self.assertTrue(report["generated"])


if __name__ == "__main__":
    unittest.main()
