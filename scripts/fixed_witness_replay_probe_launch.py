"""Frozen expected network launcher/probe relation, never a capability receipt.

The trusted host supplies the original network.chain record before launch.
Construction performs no native operation, filesystem read or filter install.
"""
from dataclasses import dataclass
import json
import re

from fixed_witness_replay_abi_model import ProbeRecord, _path
from fixed_witness_replay_network import encoded, policy_hash


@dataclass(frozen=True)
class ProbeLaunch:
    probe: ProbeRecord
    network_json: str

    def __post_init__(self):
        if (type(self.probe) is not ProbeRecord or not self.probe.valid()
                or type(self.network_json) is not str or len(self.network_json) > 65536):
            raise ValueError('frozen probe and bounded network record required')
        item = json.loads(self.network_json)
        if (type(item) is not dict or encoded(item) != self.network_json
                or set(item) != {'schema', 'python', 'python_sha256', 'launcher',
                                 'launcher_sha256', 'library', 'policy_sha256',
                                 'command', 'inherited_filters'}
                or item['schema'] != 'fixed-witness-network-v1'
                or item['policy_sha256'] != policy_hash()
                or item['python'] != self.probe.interpreter
                or item['python_sha256'] != self.probe.interpreter_sha256
                or item['command'] != list(self.probe.argv)
                or type(item['inherited_filters']) is not int
                or not 0 <= item['inherited_filters'] < 2**31
                or not _path(item['launcher'])
                or not self._hash(item['launcher_sha256'])):
            raise ValueError('network launcher does not bind the frozen probe')
        lib = item['library']
        if (type(lib) is not dict or set(lib) != {'path', 'sha256', 'version'}
                or not _path(lib['path']) or not self._hash(lib['sha256'])
                or type(lib['version']) is not list or len(lib['version']) != 3
                or any(type(v) is not int or not 0 <= v < 2**32 for v in lib['version'])):
            raise ValueError('frozen library binding required')

    @staticmethod
    def _hash(value):
        return type(value) is str and re.fullmatch('[0-9a-f]{64}', value) is not None

    def network(self):
        # A fresh parsed copy cannot mutate the original launch expectation.
        return json.loads(self.network_json)

    def argv(self):
        item = self.network()
        return (item['python'], '-I', '-B', item['launcher'], self.network_json)
