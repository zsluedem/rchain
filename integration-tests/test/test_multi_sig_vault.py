from random import Random
from rchain.crypto import PrivateKey
from docker.client import DockerClient

from .common import (
    CommandLineOptions,
)

from .conftest import (
    testing_context,
)
from .rnode import (
    started_bootstrap_with_network,
)


ALICE_KEY = PrivateKey.from_hex("b2527b00340a83e302beae2a8daf6d654e8e57541acfa261cc1b5635eb16aa15")

def create_multi_sig_vault() -> None:
    pass

def test_multi_sig_vault(command_line_options: CommandLineOptions, docker_client: DockerClient, random_generator: Random) -> None:
    genesis_vault = {
        ALICE_KEY: 5000000
    }
    with testing_context(command_line_options, random_generator, docker_client, wallets_dict=genesis_vault) as context, \
            started_bootstrap_with_network(context=context) as _:
        pass
