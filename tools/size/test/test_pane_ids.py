"""tools/pane_ids.py: which `override val id` is a pane's. Writes only a temporary folder."""
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
from pane_ids import pane_ids_in  # noqa: E402


def ids_of(kotlin):
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "A.kt"), "w") as f:
            f.write(kotlin)
        return pane_ids_in(d)


class PaneIdsTest(unittest.TestCase):
    def test_a_setup_steps_id_isnt_a_pane_even_with_a_brace_in_its_constructor(self):
        kotlin = (
            "class Step(\n    private val onDone: () -> Unit = {},\n) : SetupStep {\n"
            '    override val id = "silent_updates"\n}\n\n'
            "class Def(private val x: Int) : PaneDefinition {\n"
            '    override val id = "my-pane"\n'
            "    private val step = object : SetupStep {\n"
            '        override val id = "my-pane"\n    }\n}\n'
        )
        self.assertEqual({"my-pane"}, ids_of(kotlin))

    def test_a_pane_that_takes_a_setup_step_is_still_a_pane(self):
        kotlin = (
            "class Def(private val step: SetupStep, private val f: (Int) -> Unit = {}) : PaneDefinition {\n"
            '    override val id = "new-pane"\n}\n'
        )
        self.assertEqual({"new-pane"}, ids_of(kotlin))

    def test_a_pane_after_a_class_without_modifiers_is_still_read(self):
        kotlin = (
            'class A : PaneDefinition {\n    override val id = "a"\n}\n\n'
            'class B : PaneDefinition {\n    override val id = "b"\n}\n'
        )
        self.assertEqual({"a", "b"}, ids_of(kotlin))


if __name__ == "__main__":
    unittest.main()
