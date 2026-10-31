# The CMake project is in engine/
%global _vpath_srcdir engine

Name:           fcitx5-slm-ime
Version:        2.0.0
Release:        1%{?dist}
Summary:        Japanese input method for fcitx5 using a small language model

License:        Apache-2.0
URL:            https://github.com/oogasawa/fcitx5-slm-ime
Source0:        %{url}/archive/v%{version}/%{name}-%{version}.tar.gz
# The trained model (slm-ime-weights.bin and tokenizer.json), from the release of the same version
Source1:        %{url}/releases/download/v%{version}/slm-ime-model-%{version}.tar.gz

BuildRequires:  cmake
BuildRequires:  gcc-c++
BuildRequires:  gettext
BuildRequires:  pkgconfig(Fcitx5Core)
BuildRequires:  fcitx5-devel
BuildRequires:  eigen3-devel
BuildRequires:  json-devel
BuildRequires:  anthy-unicode-devel
Requires:       fcitx5
Requires:       slm-ime-model = %{version}-%{release}

%description
An input method engine for fcitx5 that turns typed romaji into hiragana and
converts the reading to kanji-kana text with a small Transformer model that
also reads the text before the cursor, computed on the CPU inside the engine.
The alternative candidates of a phrasal unit come from Anthy, put in order by
the model. Keys can be set by profile (Default, MS-IME, ATOK) or one by one.

%package -n slm-ime-model
Summary:        Trained model of fcitx5-slm-ime
License:        CC-BY-SA-4.0
BuildArch:      noarch

%description -n slm-ime-model
Weights and tokenizer of the conversion model of fcitx5-slm-ime: a
character-level Transformer encoder-decoder with about 60 million parameters,
trained on pairs of a reading and kanji-kana text made from the Japanese
Wikipedia and FineWeb-2 web pages.

%prep
%autosetup -n %{name}-%{version}
mkdir -p model-release
tar -xf %{SOURCE1} -C model-release

%build
%cmake
%cmake_build

%install
%cmake_install
install -D -m 644 model-release/slm-ime-weights.bin %{buildroot}%{_datadir}/slm-ime/model/slm-ime-weights.bin
install -D -m 644 model-release/tokenizer.json %{buildroot}%{_datadir}/slm-ime/model/tokenizer.json
%find_lang %{name}

%check
%ctest

%files -f %{name}.lang
%license LICENSE
%doc README.md
%{_libdir}/fcitx5/llm-ime.so
%{_datadir}/fcitx5/addon/llm-ime.conf
%{_datadir}/fcitx5/inputmethod/llm-ime.conf

%files -n slm-ime-model
%license LICENSE
%dir %{_datadir}/slm-ime
%{_datadir}/slm-ime/model/

%changelog
* Sat Oct 31 2026 Osamu Ogasawara <osamu.ogasawara@gmail.com> - 2.0.0-1
- First package for Fedora
