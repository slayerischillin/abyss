Pod::Spec.new do |s|
  s.name = 'AbyssPlayer'
  s.version = '1.0.0'
  s.summary = 'Native iPhone player for Abyss'
  s.license = 'MIT'
  s.homepage = 'https://github.com/slayerischillin/abyss'
  s.author = 'slayer'
  s.source = { :git => 'https://github.com/slayerischillin/abyss.git', :tag => s.version.to_s }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m}'
  s.ios.deployment_target = '13.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
end
