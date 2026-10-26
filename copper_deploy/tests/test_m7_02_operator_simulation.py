from __future__ import annotations

import importlib.machinery
import importlib.util
import pathlib
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader(
    "copper_m7_02_operator_simulation",
    str(ROOT / "scripts" / "m7-02-operator-simulation.py"),
)
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
assert SPEC and SPEC.loader
simulation = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = simulation
SPEC.loader.exec_module(simulation)


class M702OperatorSimulationTest(unittest.TestCase):
    def test_continuous_simulated_operator_flow_cleans_every_owned_resource(self) -> None:
        report = simulation.run_simulation()

        self.assertEqual(report["result"], "PASSED")
        self.assertEqual(report["sampleType"], "SIMULATED_OPERATOR_CONTINUOUS")
        self.assertFalse(report["humanActionRequired"])
        self.assertTrue(report["internalMilestoneUseAllowed"])
        self.assertFalse(report["formalIndependentCustomerClaimAllowed"])
        self.assertEqual(report["operatorFlow"]["selectedSshUser"], "ec2-user")
        self.assertEqual(
            report["operatorFlow"]["changedValues"],
            {
                "domain": "new.example.org",
                "publicIp": "18.142.134.27",
                "sshHost": "18.142.134.27",
            },
        )
        self.assertTrue(all(
            report["checks"][key]
            for key in (
                "sshUsernamePrompt",
                "dnsRetryInPlace",
                "dnsChangeInPlace",
                "failureCleanupExercised",
                "ownedProcessResidualZero",
                "ownedListenerResidualZero",
                "temporaryProfileResidualZero",
                "oneTimeSecretResidualZero",
            )
        ))
        self.assertFalse(report["checks"]["browserAutomationStarted"])
        self.assertFalse(report["checks"]["formalBackendPortTouched"])
        self.assertFalse(report["checks"]["containerRuntimeTouched"])


if __name__ == "__main__":
    unittest.main()
