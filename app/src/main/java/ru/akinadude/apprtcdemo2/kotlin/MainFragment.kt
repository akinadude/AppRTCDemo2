package org.appspot.apprtc.kotlin

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.fragment_main.*
import ru.akinadude.apprtcdemo2.ConnectActivity
import ru.akinadude.apprtcdemo2.R
import ru.akinadude.apprtcdemo2.kotlin.CallFragment
import ru.akinadude.apprtcdemo2.kotlin.LoopbackCallFragment

class MainFragment : Fragment() {

    companion object {

        fun newInstance(): MainFragment {
            return MainFragment()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        start_connect_activity_button.setOnClickListener {
            navigateToConnectActivity()
        }

        start_custom_call_fragment_button.setOnClickListener {
            navigateToMyCallFragment()
        }

        start_websocket_test_fragment_button.setOnClickListener {
            navigateToWebSocketTestFragment()
        }
    }

    fun navigateToConnectActivity() {
        val intent = Intent(activity, ConnectActivity::class.java)
        startActivity(intent)
    }

    fun navigateToMyCallFragment() {
        fragmentManager?.let { fm ->
            val f = LoopbackCallFragment.newInstance()
            fm.beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .addToBackStack(f.javaClass.name)
                    .commit()
        }
    }

    //todo navigate to call fragment
    fun navigateToMyCallFragment2() {
        fragmentManager?.let { fm ->
            val f = CallFragment.newInstance()
            fm.beginTransaction()
                .replace(R.id.fragment_container, f)
                .addToBackStack(f.javaClass.name)
                .commit()
        }
    }

    fun navigateToWebSocketTestFragment() {
        /*fragmentManager?.let { fm ->
            val f = WebSocketTestFragment.newInstance()
            fm.beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .addToBackStack(f.javaClass.name)
                    .commit()
        }*/
    }
}