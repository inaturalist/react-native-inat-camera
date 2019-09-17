require 'json'
package = JSON.parse(File.read('package.json'))

Pod::Spec.new do |s|

  s.name           = "react-native-inat-camera"
  s.version        = package["version"]
  s.summary        = package["description"]
  s.homepage       = "https://github.com/inaturalist/react-native-inat-camera"
  s.license        = "MIT"
  s.author         = "iNaturalist"
  s.platform       = :ios, "9.0"
  s.source         = { :git => "https://github.com/inaturalist/react-native-inat-camera.git", :tag => "master" }
  s.source_files   = "ios/**/*.{h,m}"
  s.preserve_paths = 'README.md', 'LICENSE', 'package.json', 'index.js'

  s.dependency     "React"

end