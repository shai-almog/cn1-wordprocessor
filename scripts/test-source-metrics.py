#!/usr/bin/env python3
"""Checks the measurement boundary; run in the metrics virtual environment."""
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('metrics',Path(__file__).with_name('source-metrics.py'))
metrics=importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)
class PruningTest(unittest.TestCase):
    def test_generated_unicode_table_is_reported_separately(self):
        for name in ('Unicode', 'GraphemeProperties'):
            self.assertEqual('generatedApplication', metrics.group_java(Path('common/src/main/java/com/codenameone/wordprocessor/'+name+'.java')))
        self.assertEqual('application', metrics.group_java(Path('common/src/main/java/com/codenameone/wordprocessor/TextNavigation.java')))

    def test_braces_in_strings_and_nested_modules(self):
        source=b'fn live() {}\n#[cfg(test)] mod tests { #[test] fn check() { let s = r#"}}{"#; } }\nfn also_live() {}'
        ranges, external=metrics.rust_test_regions(source)
        self.assertEqual(b'fn live() {}\n\nfn also_live() {}',metrics.prune(source,ranges))
        self.assertEqual([],external)
    def test_external_module_and_feature_gate(self):
        source=b'#[cfg(test)] mod tests; #[cfg(feature="test")] fn live() {}'
        ranges,external=metrics.rust_test_regions(source)
        self.assertEqual([(('tests',),None)],external)
        self.assertIn(b'fn live()',metrics.prune(source,ranges))
    def test_test_function_and_attributes(self):
        source=b'#[allow(dead_code)] #[test] fn check() {}\n#[cfg(not(test))] fn live() {}'
        ranges,_=metrics.rust_test_regions(source)
        self.assertEqual(b'\n#[cfg(not(test))] fn live() {}',metrics.prune(source,ranges))
if __name__=='__main__': unittest.main()
