import SwiftUI
@testable import shared

struct ContentView: View {
    var body: some View {
        VStack(spacing: 8, content: {
            Text("foo: \(com.github.jetbrains.swiftexport.foo())")
            Text("bar: \(com.github.jetbrains.swiftexport.bar())")
            Text("foobar 5: \(com.github.jetbrains.swiftexport.foobar(param: 5))")
        })
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
