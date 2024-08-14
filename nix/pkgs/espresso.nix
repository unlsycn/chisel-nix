{ stdenv
, fetchFromGitHub
, cmake
, ninja
, version ? "2.4"
, srcHash ? "sha256-z5By57VbmIt4sgRgvECnLbZklnDDWUA6fyvWVyXUzsI="
}:
stdenv.mkDerivation {
  pname = "espresso";
  inherit version;
  nativeBuildInputs = [ cmake ninja ];
  src = fetchFromGitHub {
    owner = "chipsalliance";
    repo = "espresso";
    rev = "v${version}";
    hash = srcHash;
  };
}
