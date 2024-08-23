{ lib, rustPlatform, tbConfig, enable-trace ? false }:

rustPlatform.buildRustPackage rec {
  name = "dpi-lib";
  src = ./../../gcdemu;
  cargoHash = "sha256-xta1Ao6NNbIatAHSTETxriMvfM7pZZMExh9n0Rr/s1k=";
  buildFeatures = [ "sv2023" ] ++ lib.optionals enable-trace [ "trace" ];

  env = {
    DESIGN_DATA_WIDTH = tbConfig.gcdParameter.width;
    DESIGN_TIMEOUT = tbConfig.timeout;
    DESIGN_TEST_SIZE = tbConfig.testSize;
  };

  passthru = {
    inherit enable-trace;
    inherit env;
  };
}
